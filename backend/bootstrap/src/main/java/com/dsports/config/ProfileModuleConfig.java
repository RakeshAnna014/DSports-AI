package com.dsports.config;

import com.dsports.catalog.application.result.SportResult;
import com.dsports.catalog.application.usecase.GetSportsUseCase;
import com.dsports.identity.application.port.SportTaxonomyPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Collectors;

/** Connects the identity module's shopper profiles to the catalog's sport taxonomy without either module depending on the other. */
@Configuration
public class ProfileModuleConfig {

    @Bean
    public SportTaxonomyPort sportTaxonomyPort(GetSportsUseCase getSports) {
        return () -> getSports.execute().map(SportResult::name).collect(Collectors.toSet());
    }
}
