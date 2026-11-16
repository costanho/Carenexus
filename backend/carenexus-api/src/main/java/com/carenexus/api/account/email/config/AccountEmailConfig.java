package com.carenexus.api.account.email.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccountEmailProperties.class)
public class AccountEmailConfig {
}
