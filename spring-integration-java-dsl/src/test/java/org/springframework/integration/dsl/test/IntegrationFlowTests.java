/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.dsl.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationListener;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.MessageDispatchingException;
import org.springframework.integration.annotation.MessageEndpoint;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.FixedSubscriberChannel;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.channel.DirectChannelSpec;
import org.springframework.integration.dsl.channel.MessageChannels;
import org.springframework.integration.dsl.support.Pollers;
import org.springframework.integration.endpoint.MethodInvokingMessageSource;
import org.springframework.integration.event.core.MessagingEvent;
import org.springframework.integration.event.outbound.ApplicationEventPublishingMessageHandler;
import org.springframework.integration.file.DefaultFileNameGenerator;
import org.springframework.integration.file.FileHeaders;
import org.springframework.integration.file.FileWritingMessageHandler;
import org.springframework.integration.handler.advice.ExpressionEvaluatingRequestHandlerAdvice;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.transformer.PayloadDeserializingTransformer;
import org.springframework.integration.transformer.PayloadSerializingTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

/**
 * @author Artem Bilan
 */
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
@RunWith(SpringJUnit4ClassRunner.class)
public class IntegrationFlowTests {

	private static final File tmpDir = new File(System.getProperty("java.io.tmpdir"));

	@Autowired
	private ListableBeanFactory beanFactory;

	@Autowired
	@Qualifier("flow1QueueChannel")
	private PollableChannel outputChannel;

	@Autowired
	@Qualifier("inputChannel")
	private DirectChannel inputChannel;

	@Autowired
	@Qualifier("foo")
	private PublishSubscribeChannel foo;

	@Autowired
	@Qualifier("successChannel")
	private PollableChannel successChannel;

	@Autowired
	@Qualifier("flow3Input")
	private DirectChannel flow3Input;

	@Autowired
	private AtomicReference<Object> eventHolder;

	@Autowired
	@Qualifier("bridgeFlowInput")
	private PollableChannel bridgeFlowInput;

	@Autowired
	@Qualifier("bridgeFlowOutput")
	private PollableChannel bridgeFlowOutput;

	@Autowired
	@Qualifier("bridgeFlow2Input")
	private DirectChannel bridgeFlow2Input;

	@Autowired
	@Qualifier("bridgeFlow2Output")
	private PollableChannel bridgeFlow2Output;

	@Autowired
	@Qualifier("fileFlow1Input")
	private DirectChannel fileFlow1Input;

	@Autowired
	@Qualifier("fileWritingMessageHandler")
	private FileWritingMessageHandler fileWritingMessageHandler;

	@Autowired
	@Qualifier("methodInvokingInput")
	private DirectChannel methodInvokingInput;

	@Autowired
	@Qualifier("delayedAdvice")
	private DelayedAdvice delayedAdvice;

	@Autowired
	@Qualifier("enricherInput")
	private FixedSubscriberChannel enricherInput;


	@Test
	public void testPollingFlow() {
		assertThat(this.beanFactory.getBean("integerChannel"), Matchers.instanceOf(FixedSubscriberChannel.class));
		for (int i = 0; i < 10; i++) {
			Message<?> message = this.outputChannel.receive(5000);
			assertNotNull(message);
			assertEquals("" + i, message.getPayload());
		}
	}

	@Test
	public void testDirectFlow() {
		assertTrue(this.beanFactory.containsBean("filter"));
		assertTrue(this.beanFactory.containsBean("filter.handler"));
		QueueChannel replyChannel = new QueueChannel();
		Message<String> message = MessageBuilder.withPayload("100").setReplyChannel(replyChannel).build();
		try {
			this.inputChannel.send(message);
			fail("Expected MessageDispatchingException");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageDeliveryException.class));
			assertThat(e.getCause(), Matchers.instanceOf(MessageDispatchingException.class));
			assertThat(e.getMessage(), Matchers.containsString("Dispatcher has no subscribers"));
		}
		this.beanFactory.getBean("payloadSerializingTransformer", Lifecycle.class).start();

		final AtomicBoolean used = new AtomicBoolean();

		this.foo.subscribe(m -> used.set(true));

		this.inputChannel.send(message);
		Message<?> reply = replyChannel.receive(5000);
		assertNotNull(reply);
		assertEquals(200, reply.getPayload());

		Message<?> successMessage = this.successChannel.receive(5000);
		assertNotNull(successMessage);
		assertEquals(100, successMessage.getPayload());

		assertTrue(used.get());
	}

	@Test
	public void testHandle() {
		assertNull(this.eventHolder.get());
		this.flow3Input.send(new GenericMessage<>("foo"));
		assertNotNull(this.eventHolder.get());
		assertEquals("foo", this.eventHolder.get());
	}

	@Test
	public void testBridge() {
		GenericMessage<String> message = new GenericMessage<>("test");
		this.bridgeFlowInput.send(message);
		Message<?> reply = this.bridgeFlowOutput.receive(5000);
		assertNotNull(reply);
		assertEquals("test", reply.getPayload());

		assertTrue(this.beanFactory.containsBean("bridgeFlow2:channel#0"));
		assertThat(this.beanFactory.getBean("bridgeFlow2:channel#0"), Matchers.instanceOf(FixedSubscriberChannel.class));

		try {
			this.bridgeFlow2Input.send(message);
			fail("Expected MessageDispatchingException");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageDeliveryException.class));
			assertThat(e.getCause(), Matchers.instanceOf(MessageDispatchingException.class));
			assertThat(e.getMessage(), Matchers.containsString("Dispatcher has no subscribers"));
		}
		this.beanFactory.getBean("bridge", Lifecycle.class).start();
		this.bridgeFlow2Input.send(message);
		reply = this.bridgeFlow2Output.receive(5000);
		assertNotNull(reply);
		assertEquals("test", reply.getPayload());
		assertTrue(this.delayedAdvice.getInvoked());
	}

	@Test
	public void testWrongLastComponent() {
		try {
			new AnnotationConfigApplicationContext(InvalidLastComponentFlowContext.class);
			fail("BeanCreationException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(BeanCreationException.class));
			assertThat(e.getMessage(), Matchers.containsString("is a one-way 'MessageHandler'"));
		}
	}

	@Test
	public void testWrongLastMessageChannel() {
		try {
			new AnnotationConfigApplicationContext(InvalidLastMessageChannelFlowContext.class);
			fail("BeanCreationException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(BeanCreationException.class));
			assertThat(e.getMessage(), Matchers.containsString("'.fixedSubscriberChannel()' can't be the last EIP-method in the IntegrationFlow definition"));
		}
	}


	@Test
	public void testFileHandler() {
		assertEquals(1, this.beanFactory.getBeansOfType(FileWritingMessageHandler.class).size());
		Message<?> message = MessageBuilder.withPayload("foo").setHeader(FileHeaders.FILENAME, "foo").build();
		try {
			this.fileFlow1Input.send(message);
			fail("NullPointerException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(MessageHandlingException.class));
			assertThat(e.getCause(), Matchers.instanceOf(NullPointerException.class));
		}
		this.fileWritingMessageHandler.setFileNameGenerator(new DefaultFileNameGenerator());
		this.fileFlow1Input.send(message);

		assertTrue(new File(tmpDir, "foo").exists());
	}

	@Test
	public void testMethodInvokingMessageHandler() {
		QueueChannel replyChannel = new QueueChannel();
		Message<?> message = MessageBuilder.withPayload("world").setHeader(MessageHeaders.REPLY_CHANNEL, replyChannel).build();
		this.methodInvokingInput.send(message);
		Message<?> receive = replyChannel.receive(5000);
		assertNotNull(receive);
		assertEquals("Hello, world", receive.getPayload());
	}

	@Test
	public void testWrongConfigurationWithSpecBean() {
		try {
			new AnnotationConfigApplicationContext(InvalidConfigurationWithSpec.class);
			fail("BeanCreationException expected");
		}
		catch (Exception e) {
			assertThat(e, Matchers.instanceOf(IllegalArgumentException.class));
			assertThat(e.getCause(), Matchers.instanceOf(BeanCreationException.class));
			assertThat(e.getCause().getMessage(), Matchers.containsString("must be populated to target objects via 'get()' method call"));
		}
	}

	@Test
	public void testContentEnricher() {
		QueueChannel replyChannel = new QueueChannel();
		Message<?> message = MessageBuilder.withPayload(new TestPojo("Bar")).setHeader(MessageHeaders.REPLY_CHANNEL, replyChannel).build();
		this.enricherInput.send(message);
		Message<?> receive = replyChannel.receive(5000);
		assertNotNull(receive);
		assertEquals("Bar Bar", receive.getHeaders().get("foo"));
		Object payload = receive.getPayload();
		assertThat(payload, Matchers.instanceOf(TestPojo.class));
		TestPojo result = (TestPojo) payload;
		assertEquals("Bar Bar", result.getName());
		assertNotNull(result.getDate());
		assertThat(new Date(), Matchers.greaterThan(result.getDate()));
	}


	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		public MessageSource<?> integerMessageSource() {
			MethodInvokingMessageSource source = new MethodInvokingMessageSource();
			source.setObject(new AtomicInteger());
			source.setMethodName("getAndIncrement");
			return source;
		}

		@Bean
		public IntegrationFlow flow1() {
			return IntegrationFlows.from(this.integerMessageSource(), c -> c.poller(Pollers.fixedRate(100)))
					.fixedSubscriberChannel("integerChannel")
					.transform("payload.toString()")
					.channel(MessageChannels.queue("flow1QueueChannel"))
					.get();
		}

		@Bean(name = PollerMetadata.DEFAULT_POLLER_METADATA_BEAN_NAME)
		public PollerMetadata poller() {
			return Pollers.fixedRate(500).get();
		}

		@Bean
		public DirectChannel inputChannel() {
			return MessageChannels.direct().get();
		}

		@Bean
		public PublishSubscribeChannel foo() {
			return MessageChannels.publishSubscribe().get();
		}

	}

	@Configuration
	@ComponentScan
	public static class ContextConfiguration2 {

		@Autowired
		@Qualifier("inputChannel")
		private MessageChannel inputChannel;

		@Autowired
		@Qualifier("successChannel")
		private PollableChannel successChannel;


		@Bean
		public Advice expressionAdvice() {
			ExpressionEvaluatingRequestHandlerAdvice advice = new ExpressionEvaluatingRequestHandlerAdvice();
			advice.setOnSuccessExpression("payload");
			advice.setSuccessChannel(this.successChannel);
			return advice;
		}

		@Bean
		public IntegrationFlow flow2() {
			return IntegrationFlows.from(this.inputChannel)
					.filter(p -> p instanceof String, c -> c.id("filter"))
					.channel("foo")
					.fixedSubscriberChannel()
					.<String, Integer>transform(Integer::parseInt)
					.transform(new PayloadSerializingTransformer(),
							c -> c.autoStartup(false).id("payloadSerializingTransformer"))
					.channel(MessageChannels.queue(new SimpleMessageStore(), "fooQueue"))
					.transform(new PayloadDeserializingTransformer())
					.channel(MessageChannels.publishSubscribe("publishSubscribeChannel"))
					.transform((Integer p) -> p * 2, c -> c.advice(this.expressionAdvice()))
					.get();
		}

	}

	@MessageEndpoint
	public static class AnnotationTestService {

		@ServiceActivator(inputChannel = "publishSubscribeChannel")
		public void handle(Object payload) {
			assertEquals(100, payload);
		}
	}

	@Configuration
	public static class ContextConfiguration3 {

		@Autowired
		@Qualifier("delayedAdvice")
		private MethodInterceptor delayedAdvice;

		@Bean
		public QueueChannel successChannel() {
			return MessageChannels.queue().get();
		}

		@Bean
		public AtomicReference<Object> eventHolder() {
			return new AtomicReference<>();
		}

		@Bean
		public ApplicationListener<MessagingEvent> eventListener() {
			return new ApplicationListener<MessagingEvent>() {

				@Override
				public void onApplicationEvent(MessagingEvent event) {
					eventHolder().set(event.getMessage().getPayload());
				}
			};
		}

		@Bean
		public IntegrationFlow flow3() {
			return IntegrationFlows.from("flow3Input")
					.handle(new ApplicationEventPublishingMessageHandler())
					.get();
		}

		@Bean
		public IntegrationFlow bridgeFlow() {
			return IntegrationFlows.from(MessageChannels.queue("bridgeFlowInput"))
					.channel(MessageChannels.queue("bridgeFlowOutput"))
					.get();
		}

		@Bean
		public IntegrationFlow bridgeFlow2() {
			return IntegrationFlows.from("bridgeFlow2Input")
					.bridge(c -> c.autoStartup(false).id("bridge"))
					.fixedSubscriberChannel()
					.delay("delayer", "200", c -> c.advice(this.delayedAdvice))
					.channel(MessageChannels.queue("bridgeFlow2Output"))
					.get();
		}

	}

	@Component("delayedAdvice")
	public static class DelayedAdvice implements MethodInterceptor {

		private final AtomicBoolean invoked = new AtomicBoolean();

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			this.invoked.set(true);
			return invocation.proceed();
		}

		public Boolean getInvoked() {
			return invoked.get();
		}

	}

	@Configuration
	public static class ContextConfiguration4 {

		@Bean
		public FileWritingMessageHandler fileWritingMessageHandler() {
			return new FileWritingMessageHandler(tmpDir);
		}

		@Bean
		public IntegrationFlow fileFlow1() {
			return IntegrationFlows.from("fileFlow1Input")
					.handle(this.fileWritingMessageHandler(), c -> {
						FileWritingMessageHandler handler = c.get().getT2();
						handler.setFileNameGenerator(message -> null);
						handler.setExpectReply(false);
					})
					.get();
		}

		@Bean
		public IntegrationFlow methodInvokingFlow() {
			return IntegrationFlows.from("methodInvokingInput")
					.handle("greetingService", null)
					.get();
		}

		@Bean
		public IntegrationFlow enricherFlow() {
			return IntegrationFlows.fromFixedMessageChannel("enricherInput")
					.enrich(e -> e.requestChannel("enrichChannel")
									.requestPayloadExpression("payload")
									.shouldClonePayload(false)
									.propertyExpression("name", "payload['name']")
									.propertyExpression("date", "new java.util.Date()")
									.headerExpression("foo", "payload['name']")
					)
					.get();
		}

		@Bean
		public IntegrationFlow enrichFlow() {
			return IntegrationFlows.from("enrichChannel")
					.<TestPojo, Map<?, ?>>transform(p -> Collections.singletonMap("name", p.getName() + " Bar"))
					.get();
		}

	}

	@Component("greetingService")
	public static class GreetingService {

		public String greeting(String payload) {
			return "Hello, " + payload;
		}
	}


	private static class InvalidLastComponentFlowContext {

		@Bean
		public IntegrationFlow wrongLastComponent() {
			return IntegrationFlows.from(MessageChannels.direct())
					.handle(Object::toString)
					.channel(MessageChannels.direct())
					.get();
		}

	}

	private static class InvalidLastMessageChannelFlowContext {

		@Bean
		public IntegrationFlow wrongLastComponent() {
			return IntegrationFlows.from(MessageChannels.direct())
					.fixedSubscriberChannel()
					.get();
		}

	}

	@EnableIntegration
	public static class InvalidConfigurationWithSpec {

		@Bean
		public DirectChannelSpec invalidBean() {
			return MessageChannels.direct();
		}

	}

	private static class TestPojo {

		private String name;

		private Date date;

		private TestPojo(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Date getDate() {
			return date;
		}

		public void setDate(Date date) {
			this.date = date;
		}

	}

}
