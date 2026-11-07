package social.tbone.webxdc

/** Content-addressed IDs are the first 32 lowercase hex characters of SHA-256. */
internal fun validXdcId(value: String): Boolean =
    value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun validXdcIconName(value: String): Boolean =
    value.lowercase() in setOf("icon.png", "icon.jpg", "icon.jpeg") && !value.contains('/') && !value.contains('\\')
