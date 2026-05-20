package pt.properia.api.modules.listings.application;

import org.springframework.stereotype.Service;
import pt.properia.api.modules.listings.domain.Listing;
import pt.properia.api.shared.domain.DomainException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class UpdateListingUseCase {

    private final ListingRepository repository;

    public UpdateListingUseCase(ListingRepository repository) {
        this.repository = repository;
    }

    public record Command(
        UUID listingId,
        UUID advertiserId,
        String title,
        String descriptionRaw,
        BigDecimal priceAmount,
        int bedrooms,
        BigDecimal bathrooms,
        int suites,
        BigDecimal usableAreaM2,
        BigDecimal grossAreaM2,
        BigDecimal lotAreaM2,
        Integer floorNumber,
        Integer totalFloors,
        Integer constructionYear,
        Integer renovationYear,
        String conditionDeclared,
        String furnishedDeclared,
        String energyRating,
        String sunExposure,
        boolean hasElevator,
        boolean hasBalcony,
        boolean hasTerrace,
        boolean hasGarden,
        boolean hasPool,
        boolean hasStorageRoom,
        boolean hasGarage,
        boolean hasEquippedKitchen,
        boolean hasAirConditioning,
        boolean hasSolarPanels,
        boolean hasSeaView,
        boolean hasFireplace
    ) {}

    public Listing execute(Command cmd) {
        var listing = repository.findByIdAndAdvertiserId(cmd.listingId(), cmd.advertiserId())
            .orElseThrow(() -> DomainException.notFound("Anúncio não encontrado."));

        if (cmd.title() != null && !cmd.title().isBlank()) {
            listing.setTitle(cmd.title().strip());
            listing.setTitleNormalized(cmd.title().strip().toLowerCase());
        }
        listing.setDescriptionRaw(cmd.descriptionRaw());
        listing.setPriceAmount(cmd.priceAmount());
        listing.setBedrooms(cmd.bedrooms());
        listing.setBathrooms(cmd.bathrooms() != null ? cmd.bathrooms() : BigDecimal.ZERO);
        listing.setSuites(cmd.suites());
        listing.setUsableAreaM2(cmd.usableAreaM2());
        listing.setGrossAreaM2(cmd.grossAreaM2());
        listing.setLotAreaM2(cmd.lotAreaM2());
        listing.setFloorNumber(cmd.floorNumber());
        listing.setTotalFloors(cmd.totalFloors());
        listing.setConstructionYear(cmd.constructionYear());
        listing.setRenovationYear(cmd.renovationYear());
        listing.setConditionDeclared(cmd.conditionDeclared());
        listing.setFurnishedDeclared(cmd.furnishedDeclared());
        listing.setEnergyRating(cmd.energyRating());
        listing.setSunExposure(cmd.sunExposure());
        listing.setHasElevator(cmd.hasElevator());
        listing.setHasBalcony(cmd.hasBalcony());
        listing.setHasTerrace(cmd.hasTerrace());
        listing.setHasGarden(cmd.hasGarden());
        listing.setHasPool(cmd.hasPool());
        listing.setHasStorageRoom(cmd.hasStorageRoom());
        listing.setHasGarage(cmd.hasGarage());
        listing.setHasEquippedKitchen(cmd.hasEquippedKitchen());
        listing.setHasAirConditioning(cmd.hasAirConditioning());
        listing.setHasSolarPanels(cmd.hasSolarPanels());
        listing.setHasSeaView(cmd.hasSeaView());
        listing.setHasFireplace(cmd.hasFireplace());

        return repository.save(listing);
    }
}
