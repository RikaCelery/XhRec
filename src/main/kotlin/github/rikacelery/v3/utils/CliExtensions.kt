package github.rikacelery.v3.utils

import org.apache.commons.cli.CommandLine

/**
 * Resolves a CLI option's value with a three-way precedence: the CLI flag
 * itself, then a matching XHREC_-prefixed environment variable, then the
 * given default. This lets every option also be set via the environment
 * (e.g. XHREC_PORT, XHREC_TLS) for Docker-style deployments, without
 * requiring the flag to be passed on every invocation.
 *
 * The environment variable name is derived automatically from [opt] (e.g.
 * "port" -> XHREC_PORT, "tls" -> XHREC_TLS).
 */
fun CommandLine.getOptionOrEnv(opt: String, default: String): String {
    return this.getOptionValue(opt)
        ?: System.getenv("XHREC_${opt.uppercase()}")
        ?: default
}
