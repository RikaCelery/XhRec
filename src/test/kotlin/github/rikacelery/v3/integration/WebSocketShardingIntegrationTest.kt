package github.rikacelery.v3.integration

import github.rikacelery.v3.components.LiveEventSource
import github.rikacelery.v3.components.SessionState
import github.rikacelery.v3.events.RecordingStarted
import github.rikacelery.v3.events.RoomAdded
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The platform answers only ~200–260 subscribe commands per connection and silently ignores the
 * rest (measured 2026-09-19: `106 limit exceeded`, and pacing the same channels did not buy a single
 * extra subscription — it is a total per connection, not a rate). A large subscribe burst also
 * loses frames the server never processes.
 *
 * So the shard has to (a) keep every connection inside a channel budget, (b) pace what it sends, and
 * (c) offload a room that does not fit onto another connection — opening one when none has room.
 * These tests pin all three.
 */
class WebSocketShardingIntegrationTest {

    /** The budget the production component is constructed with. */
    private val budget = 160

    @Test
    fun `the shard grows with the room list so no connection holds too many channels`() =
        testApplicationWithBudget {
            withFixture { fx ->
                fx.ready()

                // 60 tracked rooms is six status channels each — far more than one connection may
                // hold, so a single fixed pool (the shape that loses events) cannot satisfy this
                repeat(60) { index -> fx.eventBus.publish(RoomAdded(1000L + index, "model$index")) }

                // A resize reconnects every connection, so wait for the new shard to finish
                // re-subscribing the room set instead of sampling mid-rebuild — and re-announce the
                // rooms that are still missing: the event bus drops events when a subscriber is
                // behind, and a dropped `RoomAdded` leaves that room untracked (the fixture relies on
                // the same idempotent re-announce in `awaitRoomSubscribed`).
                val expected = (0 until 60).map { "broadcastChanged@${1000L + it}" }.toSet()
                val deadline = System.currentTimeMillis() + 30.seconds.inWholeMilliseconds
                while (System.currentTimeMillis() < deadline) {
                    val subscribed = fx.mock.subscribedChannels()
                    if (fx.mock.connectionCount() >= 2 && expected.all { it in subscribed }) break
                    (0 until 60)
                        .filter { "broadcastChanged@${1000L + it}" !in subscribed }
                        .forEach { fx.eventBus.publish(RoomAdded(1000L + it, "model$it")) }
                    delay(50.milliseconds)
                }

                assertTrue(
                    fx.mock.connectionCount() >= 2,
                    "a 60-room set must be sharded over several connections, got ${fx.mock.connectionCount()}"
                )
                assertTrue(
                    fx.mock.channelsPerConnection().max() <= budget,
                    "no connection may exceed $budget channels, got ${fx.mock.channelsPerConnection()}"
                )
                // every room must still be reachable: sharding moves a room, it does not drop it
                val subscribed = fx.mock.subscribedChannels()
                    .filter { it.startsWith("broadcastChanged@") }
                    .filter { it.substringAfter("@").toLongOrNull() in 1000L..1059L }
                assertEquals(
                    expected,
                    subscribed.toSet(),
                    "every room must keep its status subscription after sharding"
                )
            }
        }

    @Test
    fun `a recording room that does not fit its connection is offloaded to a new one`() =
        testApplicationWithBudget {
            // One connection may hold 70 channels and the room set is assumed to be idle, so the
            // shard starts as a single connection. Rooms going live add 26 channels each — a load
            // the shard cannot absorb, and one that never triggers a resize (only RoomAdded and
            // RoomRemoved do). It has to be offloaded by growing the shard.
            withFixture(liveEventSource = { bus, scope, provider, tuning, url ->
                LiveEventSource(
                    // the mock's default guest token; a wrong one makes it close every connection
                    tokenProvider = { "mock-ws-token" },
                    eventBus = bus,
                    parentScope = scope,
                    wsPoolCount = 1,
                    httpClientProvider = provider,
                    runtimeTuning = tuning,
                    wsUrlBuilder = url,
                    maxChannelsPerPool = 70,
                    recordingReserve = 1,
                    maxPoolCount = 40
                )
            }) { fx ->
                fx.ready()
                val rooms = (0 until 4).map { 2000L + it }
                rooms.forEach { fx.eventBus.publish(RoomAdded(it, "offload$it")) }

                val idle = rooms.map { "broadcastChanged@$it" }.toSet()
                var deadline = System.currentTimeMillis() + 20.seconds.inWholeMilliseconds
                while (System.currentTimeMillis() < deadline && !idle.all { it in fx.mock.subscribedChannels() }) {
                    delay(50.milliseconds)
                }
                assertEquals(
                    1, fx.mock.connectionCount(),
                    "four idle rooms are 24 channels and must fit the first connection"
                )

                rooms.forEach { fx.eventBus.publish(RecordingStarted(it)) }

                val full = rooms.map { "newChatMessage@$it" }.toSet()
                deadline = System.currentTimeMillis() + 20.seconds.inWholeMilliseconds
                while (System.currentTimeMillis() < deadline && !full.all { it in fx.mock.subscribedChannels() }) {
                    delay(50.milliseconds)
                }

                assertTrue(
                    full.all { it in fx.mock.subscribedChannels() },
                    "every recording room must be subscribed somewhere: ${fx.mock.subscribedChannels()}"
                )
                assertTrue(
                    fx.mock.connectionCount() >= 2,
                    "128 channels of live rooms cannot fit one 70-channel connection, got ${fx.mock.connectionCount()}"
                )
                assertTrue(
                    fx.mock.channelsPerConnection().max() <= 70,
                    "offloading must keep every connection inside the budget: ${fx.mock.channelsPerConnection()}"
                )
            }
        }

    @Test
    fun `a recording room keeps the full channel set on whichever connection owns it`() =
        testApplicationWithBudget {
            withFixture { fx ->
                fx.ready()
                fx.mock.addRoom(1001, "model", status = "off")

                fx.get("/add?name=model").expectOk("Room added: model")
                fx.awaitRoomSubscribed(1001)
                fx.get("/activate?id=1001").expectOk("Activated")
                fx.mock.setRoomStatus(1001, "public")

                fx.awaitSession(1001, SessionState.Recording)
                assertTrue(
                    fx.mock.awaitSubscription("newChatMessage@1001", 10.seconds),
                    "a recording room needs its full channel set: ${fx.mock.subscribedChannels()}"
                )
                assertTrue(
                    fx.mock.channelsPerConnection().max() <= budget,
                    "the full set must not push its connection past the budget: ${fx.mock.channelsPerConnection()}"
                )
            }
        }
}
