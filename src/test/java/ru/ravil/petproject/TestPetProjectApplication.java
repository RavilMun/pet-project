package ru.ravil.petproject;

import org.springframework.boot.SpringApplication;

public class TestPetProjectApplication {

    public static void main(String[] args) {
        SpringApplication.from(PetProjectApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
