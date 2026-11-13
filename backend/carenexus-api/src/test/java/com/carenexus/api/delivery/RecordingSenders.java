package com.carenexus.api.delivery;

import com.carenexus.api.delivery.email.EmailMessage;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.push.PushMessage;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.sms.SmsMessage;
import com.carenexus.api.delivery.sms.SmsSender;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test senders for the outbox worker: they record every message handed to them and can be scripted to fail.
 * Tests build their own {@code OutboundMessageWorker} with these instead of replacing the application's beans.
 */
public final class RecordingSenders {

    private RecordingSenders() {
    }

    /** What a scripted sender does on its next calls; when the script is empty every call succeeds. */
    public static class Script {

        private final Deque<RuntimeException> failures = new ArrayDeque<>();
        private RuntimeException always;

        /** The next call fails with this exception (calls are consumed in order). */
        public Script failNext(RuntimeException failure) {
            failures.add(failure);
            return this;
        }

        /** Every call fails with this exception. */
        public Script failAlways(RuntimeException failure) {
            this.always = failure;
            return this;
        }

        void run() {
            if (always != null) {
                throw always;
            }
            RuntimeException next = failures.poll();
            if (next != null) {
                throw next;
            }
        }
    }

    public static class Email implements EmailSender {

        public final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
        public final List<EmailMessage> attempts = new CopyOnWriteArrayList<>();
        public final Script script = new Script();
        private final String provider;
        private final boolean configured;

        public Email() {
            this("test-mail", true);
        }

        public Email(String provider, boolean configured) {
            this.provider = provider;
            this.configured = configured;
        }

        @Override
        public String providerName() {
            return provider;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public SendResult send(EmailMessage message) {
            attempts.add(message);
            script.run();
            sent.add(message);
            return new SendResult("mail-" + message.messageKey());
        }
    }

    public static class Sms implements SmsSender {

        public final List<SmsMessage> sent = new CopyOnWriteArrayList<>();
        public final Script script = new Script();
        private final String provider;
        private final boolean configured;

        public Sms() {
            this("test-sms", true);
        }

        public Sms(String provider, boolean configured) {
            this.provider = provider;
            this.configured = configured;
        }

        @Override
        public String providerName() {
            return provider;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public SendResult send(SmsMessage message) {
            script.run();
            sent.add(message);
            return new SendResult("sms-" + message.messageKey());
        }
    }

    public static class Push implements PushSender {

        public final List<PushMessage> sent = new CopyOnWriteArrayList<>();
        public final Script script = new Script();
        private final String provider;
        private final boolean configured;

        public Push() {
            this("test-push", true);
        }

        public Push(String provider, boolean configured) {
            this.provider = provider;
            this.configured = configured;
        }

        @Override
        public String providerName() {
            return provider;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public SendResult send(PushMessage message) {
            script.run();
            sent.add(message);
            return new SendResult("push-" + message.messageKey());
        }
    }
}
