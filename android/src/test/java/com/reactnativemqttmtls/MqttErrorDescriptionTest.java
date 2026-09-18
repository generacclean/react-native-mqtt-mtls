package com.reactnativemqttmtls;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.*;

/**
 * Tests for the error text the module sends to JS.
 *
 * These exist because of a real Sentry report whose entire diagnostic content was the word
 * "MqttException" — every mTLS failure mode, from a rejected broker certificate to an unreachable
 * host, arrived at the dashboard as that one string. The cause is Paho: it wraps the real failure in
 * an MqttException with reason code 0 (CLIENT_EXCEPTION), and MqttException.getMessage() does not
 * consult the cause at all — it looks the reason code up in a ResourceBundle that has no entry for
 * 0, so ResourceBundleCatalog returns its fallback, the literal "MqttException". Anything that reads
 * only the top-level message is therefore guaranteed to be uninformative, no matter what failed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MqttErrorDescriptionTest {

    @Test
    public void testPahoTopLevelMessageAloneIsUseless() {
        // Pins the premise the rest of this file rests on: if this ever stops being true, the
        // flattening below is no longer load-bearing.
        MqttException wrapped = new MqttException(new SSLHandshakeException("Chain validation failed"));
        assertEquals("MqttException", wrapped.getMessage());
    }

    @Test
    public void testChainIsFlattenedOutermostFirst() {
        CertificateException root = new CertificateException("Server certificate chain validation failed");
        SSLHandshakeException handshake = new SSLHandshakeException("Handshake aborted");
        handshake.initCause(root);
        MqttException wrapped = new MqttException(handshake);

        String description = MqttModule.describeThrowable(wrapped);

        assertTrue("Should name the Paho layer: " + description,
                description.startsWith("MqttException(reasonCode=0 CLIENT_EXCEPTION)"));
        assertTrue("Should reach the TLS layer: " + description,
                description.contains("SSLHandshakeException: Handshake aborted"));
        assertTrue("Should reach the real reason: " + description,
                description.contains("CertificateException: Server certificate chain validation failed"));
        // The redundant top-level "MqttException" message must not be repeated as a message.
        assertFalse("The bundle fallback should not be echoed: " + description,
                description.contains("CLIENT_EXCEPTION): MqttException"));
    }

    @Test
    public void testNamedReasonCodeIsSpelledOut() {
        // A broker that is up but refuses the connection reports a code with a catalog entry, so
        // both the number and its name should appear.
        MqttException notAuthorized = new MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED);

        String description = MqttModule.describeThrowable(notAuthorized);

        assertTrue("Should carry the code and its name: " + description,
                description.contains("reasonCode=5 NOT_AUTHORIZED"));
        assertTrue("Should keep Paho's own wording: " + description,
                description.contains("Not authorized"));
    }

    @Test
    public void testUnknownReasonCodeKeepsTheNumber() {
        MqttException unknown = new MqttException(31337);

        String description = MqttModule.describeThrowable(unknown);

        assertTrue("An unnamed code still has to be reported: " + description,
                description.contains("reasonCode=31337"));
    }

    @Test
    public void testExceptionWithNoMessageStillNamesItsType() {
        // Several Conscrypt handshake exceptions carry no message. The type is the only signal left,
        // so it is always included rather than emitted as an empty string.
        String description = MqttModule.describeThrowable(new IOException());

        assertEquals("IOException", description);
    }

    @Test
    public void testNullThrowableIsReportedExplicitly() {
        // Paho's connectionLost can hand us a null cause; the callback still has to say something.
        assertEquals("Unknown error (no exception reported)", MqttModule.describeThrowable(null));
    }

    @Test
    public void testCyclicCauseChainTerminates() {
        // Nothing forbids a cause chain from looping, and a hang here would be inside an error path,
        // which is the worst place to find one. The walk stops on the first repeat.
        Looping first = new Looping("first");
        Looping second = new Looping("second");
        first.setLoopCause(second);
        second.setLoopCause(first);

        String description = MqttModule.describeThrowable(first);

        assertEquals("Looping: first <- caused by Looping: second", description);
    }

    /** A throwable whose cause can be pointed back up the chain, which initCause forbids. */
    private static class Looping extends Throwable {
        private Throwable loopCause;

        Looping(String message) {
            super(message);
        }

        void setLoopCause(Throwable loopCause) {
            this.loopCause = loopCause;
        }

        @Override
        public synchronized Throwable getCause() {
            return loopCause;
        }
    }
}
