package pt.properia.api.modules.listings.interfaces;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pt.properia.api.modules.listings.application.*;
import pt.properia.api.modules.listings.interfaces.request.CreateListingRequest;
import pt.properia.api.modules.listings.interfaces.request.UpdateListingRequest;
import pt.properia.api.shared.domain.DomainException;
import pt.properia.api.shared.infrastructure.web.jwt.JwtClaims;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advertiser/listings")
public class AdvertiserListingController {

    private final GetAdvertiserListingsUseCase getListingsUseCase;
    private final CreateListingUseCase createListingUseCase;
    private final UpdateListingUseCase updateListingUseCase;
    private final PublishListingUseCase publishListingUseCase;
    private final ArchiveListingUseCase archiveListingUseCase;

    public AdvertiserListingController(
            GetAdvertiserListingsUseCase getListingsUseCase,
            CreateListingUseCase createListingUseCase,
            UpdateListingUseCase updateListingUseCase,
            PublishListingUseCase publishListingUseCase,
            ArchiveListingUseCase archiveListingUseCase) {
        this.getListingsUseCase = getListingsUseCase;
        this.createListingUseCase = createListingUseCase;
        this.updateListingUseCase = updateListingUseCase;
        this.publishListingUseCase = publishListingUseCase;
        this.archiveListingUseCase = archiveListingUseCase;
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        var listings = getListingsUseCase.execute(new GetAdvertiserListingsUseCase.Query(advertiserId));
        return ResponseEntity.ok(Map.of("data", listings));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal JwtClaims claims,
            @Valid @RequestBody CreateListingRequest req) {
        var advertiserId = resolveAdvertiserId(claims);
        var listing = createListingUseCase.execute(new CreateListingUseCase.Command(
            advertiserId, claims.userId(),
            req.businessType(), req.propertyType(), req.propertySubtype(),
            req.title(), req.descriptionRaw(), req.priceAmount(),
            req.bedrooms(), req.bathrooms(), req.suites(),
            req.usableAreaM2(), req.grossAreaM2(),
            req.city(), req.district(), req.parish(), req.postalCode(),
            req.conditionDeclared(), req.furnishedDeclared()
        ));
        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", listing.getId(),
            "publicId", listing.getPublicId(),
            "status", listing.getStatus()
        )));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody UpdateListingRequest req) {
        var advertiserId = resolveAdvertiserId(claims);
        var listing = updateListingUseCase.execute(new UpdateListingUseCase.Command(
            id, advertiserId,
            req.title(), req.descriptionRaw(), req.priceAmount(),
            req.bedrooms(), req.bathrooms(), req.suites(),
            req.usableAreaM2(), req.grossAreaM2(), req.lotAreaM2(),
            req.floorNumber(), req.totalFloors(),
            req.constructionYear(), req.renovationYear(),
            req.conditionDeclared(), req.furnishedDeclared(),
            req.energyRating(), req.sunExposure(),
            req.hasElevator(), req.hasBalcony(), req.hasTerrace(), req.hasGarden(),
            req.hasPool(), req.hasStorageRoom(), req.hasGarage(), req.hasEquippedKitchen(),
            req.hasAirConditioning(), req.hasSolarPanels(), req.hasSeaView(), req.hasFireplace()
        ));
        return ResponseEntity.ok(Map.of("data", Map.of(
            "id", listing.getId(),
            "publicId", listing.getPublicId(),
            "status", listing.getStatus()
        )));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        publishListingUseCase.execute(new PublishListingUseCase.Command(id, advertiserId));
        return ResponseEntity.ok(Map.of("data", Map.of("published", true)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> archive(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        archiveListingUseCase.execute(new ArchiveListingUseCase.Command(id, advertiserId));
        return ResponseEntity.ok(Map.of("data", Map.of("archived", true)));
    }

    private UUID resolveAdvertiserId(JwtClaims claims) {
        if (claims.activeAdvertiserId() == null) {
            throw new DomainException("FORBIDDEN", "Sem acesso a anunciante.", 403);
        }
        return claims.activeAdvertiserId();
    }
}
