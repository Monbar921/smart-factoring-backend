package ru.smart.factoring.backend.factoring.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Блокировки задач по расписанию в таблице shedlock (006-db.shedlock.xml): ночной прогон стартует на одном
 * экземпляре backend (Р-85). Время - время БД, чтобы расхождение часов экземпляров не давало двойного запуска.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfiguration {
    @Bean
    public LockProvider lockProvider(final JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }
}
