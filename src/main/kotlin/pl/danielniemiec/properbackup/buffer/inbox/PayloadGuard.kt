package pl.danielniemiec.properbackup.buffer.inbox

import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.HttpResponseException

class PayloadGuard : Handler {
    override fun handle(ctx: Context) {
        val lengthHeader = ctx.header("Content-Length") ?: return
        try {
            val len = lengthHeader.trim().toLong()
            if (len > MAX_BYTES) {
                throw HttpResponseException(413, "Payload too large — max 100 MB per upload")
            }
        } catch (e: NumberFormatException) {
            /* fall through */
        }
    }

    companion object {
        private const val MAX_BYTES = 100L * 1024 * 1024
    }
}
