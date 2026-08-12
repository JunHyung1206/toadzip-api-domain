package test.domain.ingest;

import org.springframework.stereotype.Component;
import test.domain.housing.HouseType;
import test.domain.housing.SupplyType;

import java.util.Optional;

/** 건설형 공공임대만 적재하기 위한 원천 경계. */
@Component
public class ConstructionRentalPolicy {

    private static final int PNU_LENGTH = 19;

    public Optional<IngestRejectionReason> rejectSupplyType(String sourceLabel) {
        SupplyType type = SupplyType.from(sourceLabel);
        if (type == null) {
            return Optional.of(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
        }
        if (!type.isConstructionRental()) {
            return Optional.of(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
        }
        return Optional.empty();
    }

    public Optional<IngestRejectionReason> rejectComplex(String supplyTypeLabel,
                                                          String houseTypeLabel,
                                                          String completionDate) {
        Optional<IngestRejectionReason> supplyTypeRejection = rejectSupplyType(supplyTypeLabel);
        if (supplyTypeRejection.isPresent()) {
            return supplyTypeRejection;
        }

        boolean apartment = HouseType.from(houseTypeLabel) == HouseType.APARTMENT;
        boolean completed = SourceValues.toDate(completionDate) != null
                && SourceValues.completionYear(completionDate) != null;
        return apartment || completed
                ? Optional.empty()
                : Optional.of(IngestRejectionReason.NOT_CONSTRUCTION_HOUSING);
    }

    public boolean hasValidPnu(String raw) {
        String value = SourceValues.trimToNull(raw);
        return value != null
                && value.length() == PNU_LENGTH
                && value.chars().allMatch(Character::isDigit);
    }
}
