package pl.danielniemiec.properbackup.buffer.ovh

class DevSafetyGuard(private val delegate: SwiftClient) : SwiftClient {

    private val safeMode: Boolean

    init {
        val env = System.getenv("PROPERBACKUP_ENV")
        safeMode = !env.equals("production", ignoreCase = true)
        if (safeMode) {
            println(
                "[DevSafetyGuard] Safe mode ON — DELETE and overwrite blocked. " +
                    "Set PROPERBACKUP_ENV=production to disable.",
            )
        }
    }

    override fun put(objectName: String, data: ByteArray) {
        if (safeMode && delegate.exists(objectName)) {
            throw SafetyException(
                "Overwrite blocked in safe mode: $objectName " +
                    "(OVH 90-day min storage — would generate phantom costs)",
            )
        }
        delegate.put(objectName, data)
    }

    override fun get(objectName: String): ByteArray = delegate.get(objectName)

    override fun list(): List<String> = delegate.list()

    override fun delete(objectName: String) {
        if (safeMode) {
            throw SafetyException(
                "DELETE blocked in safe mode: $objectName " +
                    "(OVH 90-day min storage — would generate phantom costs)",
            )
        }
        delegate.delete(objectName)
    }

    override fun exists(objectName: String): Boolean = delegate.exists(objectName)

    override fun headObject(objectName: String): ObjectMetadata? = delegate.headObject(objectName)

    override fun requestUnseal(objectName: String): UnsealStatus = delegate.requestUnseal(objectName)

    override fun checkUnsealStatus(objectName: String): UnsealStatus = delegate.checkUnsealStatus(objectName)

    fun isSafeMode(): Boolean = safeMode
}
