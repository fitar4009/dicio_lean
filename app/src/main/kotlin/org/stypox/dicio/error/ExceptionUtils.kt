package org.stypox.dicio.error

import java.io.PrintWriter
import java.io.StringWriter

object ExceptionUtils {
    /**
     * Recursively checks whether [throwable] or any of its causes is assignable to one of the
     * provided classes.
     *
     * @implNote taken from NewPipe, file util/ExceptionUtils.kt, created by @mauriciocolli
     */
    fun hasAssignableCause(
        throwable: Throwable?,
        vararg causesToCheck: Class<*>,
    ): Boolean {
        if (throwable == null) return false
        for (causeClass in causesToCheck) {
            if (causeClass.isAssignableFrom(throwable.javaClass)) return true
        }
        val currentCause = throwable.cause
        return if (throwable !== currentCause) {
            hasAssignableCause(currentCause, *causesToCheck)
        } else {
            false
        }
    }

    fun getStackTraceString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        return stringWriter.toString()
    }
}
