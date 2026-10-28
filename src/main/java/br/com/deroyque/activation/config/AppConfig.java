package br.com.deroyque.activation.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AppConfig {

	/** Relogio injetavel: os testes substituem por um relogio controlado. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** Cada chamada ao provedor roda em uma thread virtual (I/O bloqueante barato). */
	@Bean(destroyMethod = "close")
	ExecutorService providerCallExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}
}
