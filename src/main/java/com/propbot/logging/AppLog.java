package com.propbot.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central application logging: headline, then detail on the following line(s). Blank lines between
 * log <em>events</em> come from {@code logging.pattern.console} (e.g. {@code %n%n} after {@code %msg} /
 * {@code %wEx}), not from trailing characters here — Logback often strips trailing newlines inside
 * {@code %msg}.
 */
public final class AppLog {

    private static final String NL = System.lineSeparator();

    private final Logger backend;

    private AppLog(Logger backend) {
        this.backend = backend;
    }

    public static AppLog forClass(Class<?> type) {
        return new AppLog(LoggerFactory.getLogger(type));
    }

    public static AppLog forName(String name) {
        return new AppLog(LoggerFactory.getLogger(name));
    }

    public void info(String headline, String detail) {
        emit(backend::isInfoEnabled, backend::info, headline, detail);
    }

    /** Headline only (still followed by a blank line for separation). */
    public void info(String headlineOnly) {
        info(headlineOnly, "");
    }

    public void warn(String headline, String detail) {
        emit(backend::isWarnEnabled, backend::warn, headline, detail);
    }

    public void warn(String headlineOnly) {
        warn(headlineOnly, "");
    }

    public void warn(String headline, Throwable t) {
        warn(headline, stackTrace(t));
    }

    public void error(String headline, String detail) {
        emit(backend::isErrorEnabled, backend::error, headline, detail);
    }

    public void error(String headlineOnly) {
        error(headlineOnly, "");
    }

    public void error(String headline, String detail, Throwable t) {
        String body = detail == null ? "" : detail;
        if (t != null) {
            body = body.isEmpty() ? stackTrace(t) : body + NL + stackTrace(t);
        }
        error(headline, body);
    }

    public void error(String headline, Throwable t) {
        error(headline, stackTrace(t));
    }

    public void debug(String headline, String detail) {
        emit(backend::isDebugEnabled, backend::debug, headline, detail);
    }

    public void debug(String headlineOnly) {
        debug(headlineOnly, "");
    }

    public void trace(String headline, String detail) {
        emit(backend::isTraceEnabled, backend::trace, headline, detail);
    }

    public void trace(String headlineOnly) {
        trace(headlineOnly, "");
    }

    private void emit(BooleanSupplier enabled, Consumer<String> sink, String headline, String detail) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        String h = headline == null ? "" : headline;
        String d = detail == null ? "" : detail;
        sink.accept(h + NL + d + NL);
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }
}
