package test.domain.notice;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 한 공고버전이 담는 내용. 원천을 다시 읽었을 때 같은 pblancId 의 내용이 바뀌었는지 통째로 비교하려고 묶었다.
 * id·versionNumber·supersedes 는 버전마다 당연히 다르니 여기 들어오지 않는다.
 *
 * <p>여기 있는 값들은 전부 공고 단위다. 행이 2개 이상인 공고 52건으로 확인했을 때
 * 한 pblancId 안에서 값이 갈리는 경우가 없었다. 행마다 다른 값들은 {@link SupplyLine} 쪽에 있다.
 *
 * @param detailUrl          원천 url. 청약 사이트 원문(LH청약플러스 등). pcUrl 은 행마다 달라서 여기 안 쓴다
 */
public record NoticeSnapshot(
        NoticeChangeStatus changeStatus,
        LocalDateTime publishedAt,
        String title,
        String detailUrl,
        LocalDate applicationBeginOn,
        LocalDate applicationEndOn,
        LocalDate winnerAnnouncedOn,
        String supplyInstitutionName,
        String houseTypeName,
        String supplyTypeName,
        String contact
) {
}
