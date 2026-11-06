package com.carenexus.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class CarenexusApiApplication {

	public static void main(String[] args) {
		// Appointment times are clinic wall-clock times (carenexus.scheduling.time-zone). Use the same zone as the
		// JVM default so entity timestamps, expiry checks and the JDBC session agree with the scheduling rules.
		String zone = System.getenv().getOrDefault("SCHEDULING_TIME_ZONE", "Africa/Harare");
		TimeZone.setDefault(TimeZone.getTimeZone(zone));
		SpringApplication.run(CarenexusApiApplication.class, args);
	}

}
