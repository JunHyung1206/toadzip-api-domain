package test.domain.housing;

import java.time.LocalDate;

/**
 * 단지에서 원천을 다시 읽을 때마다 덮어쓰는 부분. 통째로 비교해서 안 바뀌었으면 UPDATE 를 아예 보내지 않는다.
 * 이름·주소·공급기관은 단지의 정체성이라 여기 들어오지 않는다.
 */
public record CatalogDetails(
        LocalDate completionDate,
        HeatingType heatingType,
        String heatingTypeName,
        Integer parkingSpaces,
        String corridorType,
        String elevatorInstallation,
        HouseType houseType,
        String houseTypeName
) {
}
