package pl.danielniemiec.properbackup.buffer.auth

import java.util.Optional
import java.util.regex.Pattern

object PasswordPolicy {

    private const val MIN_LEN = 10
    private val LOWER = Pattern.compile("[a-z]")
    private val UPPER = Pattern.compile("[A-Z]")
    private val DIGIT = Pattern.compile("[0-9]")
    private val SPECIAL = Pattern.compile("[^A-Za-z0-9]")

    @JvmStatic
    fun validate(password: String?): Optional<String> {
        if (password == null) return Optional.of("Hasło jest wymagane.")
        if (password.length < MIN_LEN) {
            return Optional.of("Hasło musi mieć co najmniej $MIN_LEN znaków.")
        }
        if (!LOWER.matcher(password).find()) {
            return Optional.of("Hasło musi zawierać małą literę (a–z).")
        }
        if (!UPPER.matcher(password).find()) {
            return Optional.of("Hasło musi zawierać wielką literę (A–Z).")
        }
        if (!DIGIT.matcher(password).find()) {
            return Optional.of("Hasło musi zawierać cyfrę (0–9).")
        }
        if (!SPECIAL.matcher(password).find()) {
            return Optional.of("Hasło musi zawierać znak specjalny (np. ! @ # …).")
        }
        return Optional.empty()
    }
}
