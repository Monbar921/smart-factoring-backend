package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.smart.factoring.backend.security.JwtPayload;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/internal/rest/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    @GetMapping("/hello")
    public String sayHello(){
        return "HELLO";
    }

    /**
     * Payload токена, с которым пришёл запрос: проверка, что gateway пропускает токен до backend.
     */
    @GetMapping("/whoami")
    public Map<String, Object> whoAmI(final JwtPayload user) {
        return user.claims();
    }
}
