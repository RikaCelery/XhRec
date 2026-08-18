package github.rikacelery.v3.api

import github.rikacelery.v3.exceptions.DeletedException
import github.rikacelery.v3.exceptions.RenameException
import org.junit.jupiter.api.Test
import kotlin.test.*

class ApiClientTest {

    @Test
    fun `404 rename description throws RenameException with new name`() {
        val e = assertFailsWith<RenameException> {
            throwBroadcast404("""{"description":"Model has new name: newName=NewModel123"}""")
        }
        assertEquals("NewModel123", e.newName)
    }

    @Test
    fun `404 deleted description throws DeletedException`() {
        assertFailsWith<DeletedException> {
            throwBroadcast404("""{"description":"model already deleted"}""")
        }
    }

    @Test
    fun `404 non-json body throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            throwBroadcast404("<html>cloudflare block page</html>")
        }
    }

    @Test
    fun `404 unknown description throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            throwBroadcast404("""{"description":"something unexpected"}""")
        }
    }
}
