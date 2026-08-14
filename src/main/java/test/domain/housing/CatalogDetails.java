package test.domain.housing;

import java.time.LocalDate;

/**
 * 단지에서 원천을 다시 읽을 때마다 덮어쓰는 부분. 통째로 비교해서 안 바뀌었으면 UPDATE 를 아예 보내지 않는다.
 * 이름·주소·공급기관은 단지의 정체성이라 여기 들어오지 않는다.
 *
 * <p>난방·주택유형은 원천이 준 한글 이름을 그대로 담는다. 표준 enum 으로 옮기던 칸은 없앴다 —
 * 원문과 표준값을 둘 다 들고 있으면 조회마다 어느 쪽을 봐야 하는지 정해야 했고, 원문만 남겨도
 * 화면 분기는 문자열 비교로 된다.
 */
public record CatalogDetails(
        LocalDate completionDate,
        String heatingTypeName,
        Integer parkingSpaces,
        String corridorType,
        String elevatorInstallation,
        String houseTypeName
) {
}
