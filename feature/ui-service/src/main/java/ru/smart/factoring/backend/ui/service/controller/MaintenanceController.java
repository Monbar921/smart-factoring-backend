package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/rest/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    @GetMapping("/hello")
    public String sayHello(){
        return "HELLO";
    }
}
