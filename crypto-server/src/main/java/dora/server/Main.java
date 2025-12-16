package dora.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
@SpringBootApplication
@EnableJpaRepositories(basePackages = "dora.server")
@EntityScan(basePackages = "dora.server")
@EnableAsync
@EnableKafka
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class);
    }
}
