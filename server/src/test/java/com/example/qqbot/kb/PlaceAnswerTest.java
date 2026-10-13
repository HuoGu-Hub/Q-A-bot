package com.example.qqbot.kb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「哪里有 X」的组装。
 *
 * <p>用的是一个**手写的假目录**而不是 Mockito —— 这个测试关心的是"拿到点位之后怎么组织答案"，
 * 用假数据把三个来源（wiki 点、备注别名、自定义点）的差异抹平，正好也验证了
 * 契约层的假设：目录只暴露 PlacePoint，谁也不知道它从哪来。
 */
class PlaceAnswerTest {

    private static PlacePoint p(String name, List<String> aliases, double x, double y,
                                String category, boolean landmark, String ref) {
        return new PlacePoint("Embervale", "Map:Embervale/Background", name, aliases,
                x, y, category, "", landmark, 0, "admin", ref);
    }

    /** 只按 matches 过滤的最小实现 —— 排序交给真实实现，这里不重复 */
    private static PlaceDirectory directory(List<PlacePoint> points) {
        return new PlaceDirectory() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<PlacePoint> all() {
                return points;
            }

            @Override
            public List<PlacePoint> find(String term, int limit) {
                List<PlacePoint> out = new ArrayList<>();
                for (PlacePoint point : points) {
                    if (point.matches(term)) {
                        out.add(point);
                    }
                }
                return out.size() > limit ? out.subList(0, limit) : out;
            }

            @Override
            public Map<String, double[]> worldSizes() {
                return Map.of("Embervale", new double[]{10240, 10240});
            }
        };
    }

    private static final PlacePoint SPIRE = p("远古尖塔·春之原野", List.of("Ancient Spire"),
            3508, 1982, PlaceCategory.LANDMARK, true, "wiki:1");
    private static final PlacePoint IRON = p("铁矿", List.of("iron"), 3720, 2210,
            PlaceCategory.RESOURCE, false, "place:1");

    @Test
    @DisplayName("问到资源点：给的是相对最近地标的方位，而不是一串坐标")
    void answersWithRelativePosition() {
        PlaceAnswer.Answer a = PlaceAnswer.answer("铁矿", directory(List.of(SPIRE, IRON)), 5);
        assertThat(a.found()).isTrue();
        assertThat(a.text())
                .contains("远古尖塔")
                .contains("铁矿")
                .contains("坐标 (3720, 2210)");
        // 距离是算出来的，不是编的
        assertThat(a.hits().get(0).neighbour()).isNotNull();
        assertThat(a.hits().get(0).neighbour().anchor().name()).isEqualTo("远古尖塔·春之原野");
    }

    @Test
    @DisplayName("别名也能命中 —— 群友不会照着 wiki 的英文名问")
    void matchesAlias() {
        PlaceAnswer.Answer a = PlaceAnswer.answer("iron", directory(List.of(SPIRE, IRON)), 5);
        assertThat(a.found()).isTrue();
        assertThat(a.text()).contains("铁矿");
    }

    @Test
    @DisplayName("没查到就说没查到，并指出点位是可以补的")
    void notFound() {
        PlaceAnswer.Answer a = PlaceAnswer.answer("秘银矿", directory(List.of(SPIRE, IRON)), 5);
        assertThat(a.found()).isFalse();
        assertThat(a.text()).contains("没有标记过").contains("管理员");
    }

    @Test
    @DisplayName("目录不可用与没查到是两回事 —— 排查方向完全不同")
    void unavailable() {
        PlaceDirectory down = new PlaceDirectory() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public List<PlacePoint> all() {
                return List.of();
            }

            @Override
            public List<PlacePoint> find(String term, int limit) {
                return List.of();
            }

            @Override
            public Map<String, double[]> worldSizes() {
                return Map.of();
            }
        };
        PlaceAnswer.Answer a = PlaceAnswer.answer("铁矿", down, 5);
        assertThat(a.found()).isFalse();
        assertThat(a.text()).contains("暂时不可用");
    }

    @Test
    @DisplayName("同名优先，其次按离参照物的距离排序")
    void exactNameFirst() {
        PlacePoint exact = p("铁矿", List.of(), 9000, 9000, PlaceCategory.RESOURCE, false, "place:2");
        PlacePoint partial = p("铁矿洞窟", List.of(), 3600, 2000, PlaceCategory.POI, false, "place:3");
        PlaceAnswer.Answer a = PlaceAnswer.answer("铁矿", directory(List.of(partial, exact)), 5);
        assertThat(a.hits()).hasSize(2);
        assertThat(a.hits().get(0).place().name()).isEqualTo("铁矿");
    }

    @Test
    @DisplayName("条数受限，不会把上千个点一次性倒给模型")
    void respectsLimit() {
        List<PlacePoint> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(p("铁矿" + i, List.of(), 3000 + i * 10, 2000, PlaceCategory.RESOURCE, false, "place:" + i));
        }
        many.add(SPIRE);
        PlaceAnswer.Answer a = PlaceAnswer.answer("铁矿", directory(many), 3);
        assertThat(a.hits()).hasSize(3);
    }

    @Test
    @DisplayName("空词直接拒绝，不去扫全库")
    void blankTerm() {
        PlaceAnswer.Answer a = PlaceAnswer.answer("   ", directory(List.of(SPIRE)), 5);
        assertThat(a.found()).isFalse();
        assertThat(a.text()).contains("没有给出");
    }
}
