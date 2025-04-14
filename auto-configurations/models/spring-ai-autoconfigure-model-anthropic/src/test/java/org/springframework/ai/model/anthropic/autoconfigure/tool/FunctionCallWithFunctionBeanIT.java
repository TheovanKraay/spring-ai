/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.model.anthropic.autoconfigure.tool;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.anthropic.autoconfigure.tool.MockWeatherService.Request;
import org.springframework.ai.model.anthropic.autoconfigure.tool.MockWeatherService.Response;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
class FunctionCallWithFunctionBeanIT {

	private final Logger logger = LoggerFactory.getLogger(FunctionCallWithFunctionBeanIT.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withPropertyValues("spring.ai.anthropic.apiKey=" + System.getenv("ANTHROPIC_API_KEY"))
		.withConfiguration(AutoConfigurations.of(AnthropicChatAutoConfiguration.class))
		.withUserConfiguration(Config.class);

	@Test
	void functionCallTest() {

		this.contextRunner
			.withPropertyValues(
					"spring.ai.anthropic.chat.options.model=" + AnthropicApi.ChatModel.CLAUDE_3_5_HAIKU.getValue())
			.run(context -> {

				AnthropicChatModel chatModel = context.getBean(AnthropicChatModel.class);

				var userMessage = new UserMessage(
						"What's the weather like in San Francisco, in Paris, France and in Tokyo, Japan? Return the temperature in Celsius.");

				ChatResponse response = chatModel.call(new Prompt(List.of(userMessage),
						AnthropicChatOptions.builder().function("weatherFunction").build()));

				logger.info("Response: {}", response);

				assertThat(response.getResult().getOutput().getText()).contains("30", "10", "15");

				response = chatModel.call(new Prompt(List.of(userMessage),
						AnthropicChatOptions.builder().function("weatherFunction3").build()));

				logger.info("Response: {}", response);

				assertThat(response.getResult().getOutput().getText()).contains("30", "10", "15");

			});
	}

	@Test
	void functionCallWithPortableFunctionCallingOptions() {

		this.contextRunner
			.withPropertyValues(
					"spring.ai.anthropic.chat.options.model=" + AnthropicApi.ChatModel.CLAUDE_3_5_HAIKU.getValue())
			.run(context -> {

				AnthropicChatModel chatModel = context.getBean(AnthropicChatModel.class);

				var userMessage = new UserMessage(
						"What's the weather like in San Francisco, in Paris, France and in Tokyo, Japan? Return the temperature in Celsius.");

				ChatResponse response = chatModel.call(new Prompt(List.of(userMessage),
						ToolCallingChatOptions.builder().toolNames("weatherFunction").build()));

				logger.info("Response: {}", response);

				assertThat(response.getResult().getOutput().getText()).contains("30", "10", "15");
			});
	}

	@Configuration
	static class Config {

		@Bean
		@Description("Get the weather in location. Return temperature in 36°F or 36°C format.")
		public Function<Request, Response> weatherFunction() {
			return new MockWeatherService();
		}

		// Relies on the Request's JsonClassDescription annotation to provide the
		// function description.
		@Bean
		public Function<MockWeatherService.Request, MockWeatherService.Response> weatherFunction3() {
			MockWeatherService weatherService = new MockWeatherService();
			return (weatherService::apply);
		}

	}

}
