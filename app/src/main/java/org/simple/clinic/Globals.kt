package org.simple.clinic

const val MAX_ALLOWED_PATIENT_AGE: Int = 120
const val MIN_ALLOWED_PATIENT_AGE: Int = 1
const val SECURITY_PIN_LENGTH: Int = 4
const val LOGIN_OTP_LENGTH = 6

inline fun <T> measure(
    block: () -> T,
    report: (Long) -> Unit = {}
): T {
  val now = System.currentTimeMillis()
  val value = block()
  val timeTaken = System.currentTimeMillis() - now

  report(timeTaken)

  return value
}
