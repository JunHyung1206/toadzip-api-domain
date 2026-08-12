package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SupplyLineRepository extends JpaRepository<SupplyLine, Long> {

    List<SupplyLine> findByNoticeVersionOrderByDisplayOrder(NoticeVersion noticeVersion);

    /** 단지를 못 붙였지만 PNU 는 있는 공급행. 단지가 나중에 적재되면 이것들만 다시 붙이면 된다. */
    List<SupplyLine> findByComplexIsNullAndSuppliedHousingPnuIsNotNull();

    /**
     * 공고가 지목한 지역 코드 목록. PNU 앞 5자리가 그대로 단지 API 의 brtcCode(2) + signguCode(3) 다.
     * 단지 API 는 시군구 단위로만 열려 있어서 전국을 받으려면 코드 목록이 필요한데, 공고에서 뽑으면
     * 별도 코드표 없이도 "공고가 실제로 가리키는 지역"을 정확히 얻는다.
     */
    @Query(value = "select distinct substring(supplied_pnu, 1, 5) from supply_line "
            + "where supplied_pnu is not null order by 1", nativeQuery = true)
    List<String> findDistinctRegionCodes();
}
