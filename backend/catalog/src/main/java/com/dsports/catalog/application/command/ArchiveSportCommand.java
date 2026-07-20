package com.dsports.catalog.application.command;

import com.dsports.catalog.domain.model.SportId;

public record ArchiveSportCommand(
    SportId sportId
) {}
