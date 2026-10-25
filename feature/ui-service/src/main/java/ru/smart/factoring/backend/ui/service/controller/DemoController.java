package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.smart.factoring.backend.api.demo.DemoImportRequest;
import ru.smart.factoring.backend.api.demo.DemoImportResultDto;
import ru.smart.factoring.backend.factoring.parameter.ParameterService;
import ru.smart.factoring.backend.factoring.source.DemoImportService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Служебное: импорт демо-данных (ДД-1…ДД-9) и параметры (Приложение А).
 */
@Validated
@RestController
@RequiredArgsConstructor
public class DemoController {
    private final DemoImportService demoImportService;
    private final ParameterService parameterService;

    /**
     * Импорт выгрузки генератора из каталога импорта на сервере. Заменяет демо-набор и сбрасывает прогоны (Р-30).
     */
    @PostMapping(value = "/internal/rest/demo/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DemoImportResultDto importGenerator(@RequestBody final DemoImportRequest request) {
        return demoImportService.importGenerator(demoImportService.resolve(request.path()));
    }

    /**
     * Импорт выгрузки генератора ZIP-архивом.
     */
    @PostMapping(value = "/internal/rest/demo/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DemoImportResultDto importGeneratorZip(@RequestParam("file") final MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return demoImportService.importGeneratorZip(in);
        }
    }

    /**
     * Импорт events.csv (ЕГРЮЛ, ЕФРСБ) из каталога импорта на сервере.
     */
    @PostMapping(value = "/internal/rest/demo/import/efrsb", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DemoImportResultDto importEfrsb(@RequestBody final DemoImportRequest request) {
        return demoImportService.importEfrsb(demoImportService.resolve(request.path()));
    }

    @PostMapping(value = "/internal/rest/demo/import/efrsb", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DemoImportResultDto importEfrsbFile(@RequestParam("file") final MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return demoImportService.importEfrsbFile(in);
        }
    }

    @GetMapping("/internal/rest/parameters")
    public List<ParameterService.ParameterRow> parameters() {
        return parameterService.findAll();
    }
}
