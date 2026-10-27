package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.smart.factoring.backend.api.manualupload.ManualUploadRequest;
import ru.smart.factoring.backend.api.manualupload.ManualUploadResultDto;
import ru.smart.factoring.backend.factoring.parameter.ParameterService;
import ru.smart.factoring.backend.factoring.source.ManualUploadService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Служебное: импорт данных ручной загрузки (ДД-1…ДД-9) и параметры (Приложение А).
 */
@Validated
@RestController
@RequiredArgsConstructor
public class ManualUploadController {
    private final ManualUploadService manualUploadService;
    private final ParameterService parameterService;

    /**
     * Импорт выгрузки генератора из каталога импорта на сервере: дельта - добавляет и обновляет, ничего не стирает (Р-52).
     */
    @PostMapping(value = "/internal/rest/manual-upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ManualUploadResultDto importGenerator(@RequestBody final ManualUploadRequest request) {
        return manualUploadService.importGenerator(manualUploadService.resolve(request.path()));
    }

    /**
     * Импорт выгрузки генератора ZIP-архивом.
     */
    @PostMapping(value = "/internal/rest/manual-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ManualUploadResultDto importGeneratorZip(@RequestParam("file") final MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return manualUploadService.importGeneratorZip(in);
        }
    }

    /**
     * Импорт events.csv (ЕГРЮЛ, ЕФРСБ) из каталога импорта на сервере.
     */
    @PostMapping(value = "/internal/rest/manual-upload/efrsb", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ManualUploadResultDto importEfrsb(@RequestBody final ManualUploadRequest request) {
        return manualUploadService.importEfrsb(manualUploadService.resolve(request.path()));
    }

    @PostMapping(value = "/internal/rest/manual-upload/efrsb", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ManualUploadResultDto importEfrsbFile(@RequestParam("file") final MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return manualUploadService.importEfrsbFile(in);
        }
    }

    /**
     * Полная очистка данных ручной загрузки: источники, загруженные данные, прогоны и решения менеджеров (Р-52). Параметры остаются.
     */
    @DeleteMapping("/internal/rest/manual-upload/data")
    public ResponseEntity<Void> reset() {
        manualUploadService.reset();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/internal/rest/parameters")
    public List<ParameterService.ParameterRow> parameters() {
        return parameterService.findAll();
    }
}
