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
    private final PatchListingService patchListingService;

    public AdvertiserListingController(
            GetAdvertiserListingsUseCase getListingsUseCase,
            CreateListingUseCase createListingUseCase,
            UpdateListingUseCase updateListingUseCase,
            PublishListingUseCase publishListingUseCase,
            ArchiveListingUseCase archiveListingUseCase,
            PatchListingService patchListingService) {
        this.getListingsUseCase = getListingsUseCase;
        this.createListingUseCase = createListingUseCase;
        this.updateListingUseCase = updateListingUseCase;
        this.publishListingUseCase = publishListingUseCase;
        this.archiveListingUseCase = archiveListingUseCase;
        this.patchListingService = patchListingService;
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
            req.title(), req.descriptionRaw(), req.descriptionShort(),
            req.priceAmount(),
            req.bedrooms(), req.bathrooms(), req.suites(),
            req.garageSpaces(), req.parkingSpaces(),
            req.usableAreaM2(), req.grossAreaM2(), req.lotAreaM2(),
            req.landType(), req.ceilingHeightM(), req.waterSource(), req.agriculturalUse(),
            req.city(), req.district(), req.municipality(),
            req.parish(), req.neighborhood(), req.street(),
            req.postalCode(), req.latitude(), req.longitude(), req.locationPrecision(),
            req.conditionDeclared(), req.furnishedDeclared(),
            req.energyRating(), req.energyCertificateNumber(),
            req.energyCertificateValidUntil(), req.energyCertificateExemptionReason(),
            req.youtubeVideoUrl(), req.alRegistrationNumber(),
            req.licencaUtilizacao(),
            req.terraceAreaM2(), req.gardenAreaM2(),
            req.heatingType(), req.coolingType(), req.waterHeatingType(),
            req.wcServico(), req.tipoCaixilharia(), req.localizacaoEdificio(),
            req.seguroCondominioIncluido(),
            req.exclusiveListing(),
            req.fibraOtica(), req.gasCanalizado(), req.tvCabo(), req.fossaSeptica(),
            req.isFeatured()
        ));
        return ResponseEntity.status(201).body(Map.of("data", Map.of(
            "id", listing.getId(),
            "publicId", listing.getPublicId(),
            "status", listing.getStatus(),
            "advertiserId", listing.getAdvertiserId(),
            "title", listing.getTitle(),
            "businessType", listing.getBusinessType(),
            "propertyType", listing.getPropertyType()
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
            req.landType(), req.ceilingHeightM(), req.waterSource(), req.agriculturalUse(),
            req.floorNumber(), req.totalFloors(),
            req.constructionYear(), req.renovationYear(),
            req.conditionDeclared(), req.furnishedDeclared(),
            req.energyRating(), req.sunExposure(),
            req.hasElevator(), req.hasBalcony(), req.hasTerrace(), req.hasGarden(),
            req.hasPool(), req.hasStorageRoom(), req.hasGarage(), req.hasEquippedKitchen(),
            req.hasAirConditioning(), req.hasSolarPanels(), req.hasSeaView(), req.hasFireplace(),
            req.isFeatured()
        ));
        return ResponseEntity.ok(Map.of("data", Map.of(
            "id", listing.getId(),
            "publicId", listing.getPublicId(),
            "status", listing.getStatus()
        )));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDetail(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims) {
        var advertiserId = resolveAdvertiserId(claims);
        var result = patchListingService.getForEdit(id, advertiserId);
        return ResponseEntity.ok(Map.of("data", result));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtClaims claims,
            @RequestBody Map<String, Object> body) {
        var advertiserId = resolveAdvertiserId(claims);
        var result = patchListingService.patch(id, advertiserId, body);
        return ResponseEntity.ok(Map.of("data", result));
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
