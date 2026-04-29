package com.propbot.propbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({GroupMeProperties.class, GoogleSheetsProperties.class})
public class PropbotApplication {

	public static void main(String[] args) {
		SpringApplication.run(PropbotApplication.class, args);
	}

}
