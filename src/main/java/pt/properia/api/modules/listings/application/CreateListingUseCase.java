package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class CreateListingUseCase {

    private final ListingRepository repository;

    public CreateListingUseCase(ListingRepository repository) {
        this.repository = repository;
    }

    public record Command(
        UUID advertiserId,
        UUID ownerUserId,
        String businessType,
        String propertyType,
        String propertySubtype,
        String title,
        String descriptionRaw,
        BigDecimal priceAmount,
        Integer bedrooms,
        BigDecimal bathrooms,
        Integer suites,
        BigDecimal usableAreaM2,
        BigDecimal grossAreaM2,
        String city,
        String district,
        String parish,
        String postalCode,
        String conditionDeclared,
        String furnishedDeclared,
        Boolean isFeatured
    ) {}

    public Listing execute(Command cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw new DomainException("VALIDATION_ERROR", "O título é obrigatório.");
        }
        if (cmd.businessType() == null) {
            throw new DomainException("VALIDATION_ERROR", "O tipo de negócio é obrigatório.");
        }
        if (cmd.propertyType() == null) {
            throw new DomainException("VALIDATION_ERROR", "O tipo de imóvel é obrigatório.");
        }

        var listing = new Listing();
        listing.setPublicId(generatePublicId());
        listing.setAdvertiserId(cmd.advertiserId());
        listing.setOwnerUserId(cmd.ownerUserId());
        listing.setBusinessType(cmd.businessType());
        listing.setPropertyType(cmd.propertyType());
        listing.setPropertySubtype(cmd.propertySubtype());
        listing.setTitle(cmd.title().strip());
        listing.setTitleNormalized(cmd.title().strip().toLowerCase());
        listing.setDescriptionRaw(cmd.descriptionRaw());
        listing.setPriceAmount(cmd.priceAmount());
        listing.setBedrooms(cmd.bedrooms() != null ? cmd.bedrooms() : 0);
        listing.setBathrooms(cmd.bathrooms() != null ? cmd.bathrooms() : BigDecimal.ZERO);
        listing.setSuites(cmd.suites() != null ? cmd.suites() : 0);
        listing.setUsableAreaM2(cmd.usableAreaM2());
        listing.setGrossAreaM2(cmd.grossAreaM2());
        listing.setCity(cmd.city());
        listing.setDistrict(cmd.district());
        listing.setParish(cmd.parish());
        listing.setPostalCode(cmd.postalCode());
        listing.setConditionDeclared(cmd.conditionDeclared());
        listing.setFurnishedDeclared(cmd.furnishedDeclared());
        if (Boolean.TRUE.equals(cmd.isFeatured())) listing.setFeatured(true);
        listing.setStatus("draft");

        return repository.save(listing);
    }

    private String generatePublicId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
