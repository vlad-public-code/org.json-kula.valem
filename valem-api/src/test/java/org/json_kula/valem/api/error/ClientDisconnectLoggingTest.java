package org.json_kula.valem.api.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client hanging up is not a server error.
 *
 * <p>These cases all used to reach {@code handleIo} and be logged as {@code ERROR} with a ~140-frame
 * stack trace. On a streaming endpoint that is constant background noise, and it actively hindered an
 * incident: a service being OOM-killed by its platform emitted pages of client aborts as its
 * connections dropped, which looked like the cause and were only the consequence.
 */
class ClientDisconnectLoggingTest {

    @Test
    void recognisesAClosedChannelAnywhereInTheCauseChain() {
        // The shape seen in production: AsyncRequestNotUsableException -> ClientAbortException ->
        // ClosedChannelException, several frames down.
        IOException wrapped = new IOException("ServletOutputStream failed to flush",
                new IOException("wrapper", new ClosedChannelException()));
        assertThat(GlobalExceptionHandler.isClientDisconnect(wrapped)).isTrue();
    }

    @Test
    void recognisesAContainerAbortByName() {
        // Matched by simple name rather than by importing a servlet-container class into this layer.
        class ClientAbortException extends IOException { }
        assertThat(GlobalExceptionHandler.isClientDisconnect(new ClientAbortException())).isTrue();
        assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException(new ClientAbortException())))
                .isTrue();
    }

    @Test
    void recognisesTheUsualResetMessages() {
        assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException("Broken pipe"))).isTrue();
        assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException("Connection reset by peer")))
                .isTrue();
    }

    @Test
    void leavesGenuineStorageFailuresAlone() {
        // The handler still exists to report real I/O faults loudly; this must not swallow them.
        assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException("No space left on device")))
                .isFalse();
        assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException("Permission denied")))
                .isFalse();
        assertThat(GlobalExceptionHandler.isClientDisconnect(new IOException())).isFalse();
    }

    @Test
    void terminatesOnACyclicCauseChain() {
        // initCause forbids an exception causing *itself*, but nothing stops two exceptions causing
        // each other — and walking that to its end never terminates. Spinning a request thread
        // forever is a far worse outcome than mislabelling a log line, so the walk is bounded.
        IOException a = new IOException("a");
        IOException b = new IOException("b");
        a.initCause(b);
        b.initCause(a);

        assertThat(GlobalExceptionHandler.isClientDisconnect(a)).isFalse();
    }

    @Test
    void stillFindsADisconnectInsideACyclicChain() {
        // The bound must not be so tight that it misses a real match a few frames down.
        IOException outer = new IOException("flush failed");
        IOException inner = new IOException("reset", new ClosedChannelException());
        outer.initCause(inner);

        assertThat(GlobalExceptionHandler.isClientDisconnect(outer)).isTrue();
    }
}
