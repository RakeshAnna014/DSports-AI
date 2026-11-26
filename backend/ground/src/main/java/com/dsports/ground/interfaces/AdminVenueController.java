package com.dsports.ground.interfaces;

import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageCourtsUseCase;
import com.dsports.ground.application.usecase.ManageVenuesUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator API for venues: create or edit a venue, its courts and blackouts on an owner's behalf, and reassign a venue to another ground owner.
 * The security config requires ADMIN for everything under {@code /api/v1/admin}; an administrator is not subject to the per-venue ownership check.
 */
@RestController
@RequestMapping(path = "/api/v1/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Venue administration")
public class AdminVenueController extends VenueManagementController {

    public AdminVenueController(ManageVenuesUseCase manageVenues, ManageCourtsUseCase manageCourts, ManageBlackoutsUseCase manageBlackouts) {
        super(manageVenues, manageCourts, manageBlackouts);
    }

    @Override
    protected boolean administrator() {
        return true;
    }
}
