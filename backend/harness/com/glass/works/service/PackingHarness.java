package com.glass.works.service;

import com.glass.works.service.PackingEngine.Grid;
import com.glass.works.service.PackingEngine.Placed;
import com.glass.works.service.PackingEngine.RawRun;
import com.glass.works.service.PackingEngine.Unit;
import java.util.ArrayList;
import java.util.List;

/** 人工校验入口：与 Node 原型 (src/test-proto/run.cjs) 同场景断言。仅手动运行，不进打包。 */
public class PackingHarness {

    static int failures = 0;

    record Spec(int l, int w, int qty) {
    }

    static List<Unit> expand(List<Spec> specs, boolean globalRot) {
        List<Unit> units = new ArrayList<>();
        int id = 0;
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            for (int q = 0; q < s.qty(); q++) {
                units.add(new Unit(id++, s.l(), s.w(), i, "", globalRot));
            }
        }
        return units;
    }

    static void check(String name, boolean cond) {
        check(name, cond, null);
    }

    static void check(String name, boolean cond, String detail) {
        if (!cond) {
            failures++;
            System.out.println("  !! 断言失败: " + name + " " + (detail == null ? "" : detail));
        }
    }

    static void assertGeometry(List<Placed> placed, int L, int W, int kerf) {
        for (Placed p : placed) {
            check("越界", p.x >= -1e-6 && p.y >= -1e-6
                    && p.x + p.w <= L + 1e-6 && p.y + p.h <= W + 1e-6,
                    String.format("%.0f,%.0f %.0fx%.0f in %dx%d", p.x, p.y, p.w, p.h, L, W));
        }
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                Placed a = placed.get(i);
                Placed b = placed.get(j);
                double gx = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
                double gy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
                check("刀缝/重叠", gx <= -kerf + 1e-6 || gy <= -kerf + 1e-6,
                        String.format("gap=(%.1f,%.1f) kerf=%d", -gx, -gy, kerf));
            }
        }
    }

    record Eval(RawRun run, Grid grid, int total, double pieceArea, double reuseArea,
                double kerfArea, double util, double eff, double minSide, double[] biggest) {
    }

    static Eval eval(RawRun run, List<Unit> units, int L, int W, int kerf) {
        Grid g = Grid.build(L, W, run.placed, run.frees);
        double total = L * W;
        assertGeometry(run.placed, L, W, kerf);
        check("面积闭合", Math.abs(g.pieceArea + g.freeArea + g.kerfArea - total) <= 1.0,
                String.format("%.3f+%.3f+%.3f vs %.0f", g.pieceArea, g.freeArea, g.kerfArea, total));
        double placedArea = run.placed.stream().mapToDouble(p -> p.w * p.h).sum();
        check("网格出片面积一致", Math.abs(placedArea - g.pieceArea) <= 0.01,
                placedArea + " vs " + g.pieceArea);
        double[] big = g.biggestReuseRect();
        double minSide = big == null ? 0 : Math.min(big[2], big[3]);
        return new Eval(run, g, units.size(), g.pieceArea, g.reuseArea, g.kerfArea,
                g.pieceArea / total * 100, (g.pieceArea + g.reuseArea) / total * 100, minSide, big);
    }

    static void dump(String tag, Eval e) {
        System.out.printf("  [%s] 放入 %d/%d 名义 %.2f%% 有效 %.2f%% 刀缝 %.3f㎡ 可复用 %.3f㎡/余 %.3f%s%n",
                tag, e.run().placed.size(), e.total(), e.util(), e.eff(),
                e.kerfArea() / 1e6, e.reuseArea() / 1e6, e.grid().freeArea / 1e6,
                e.run().timedOut ? " [超时-不完整]" : "");
        if (e.biggest() != null) {
            System.out.printf("        最大整块 %.0fx%.0f (%.3f㎡)%n",
                    e.biggest()[2], e.biggest()[3], e.biggest()[2] * e.biggest()[3] / 1e6);
        }
        e.run().unplaced.forEach(u -> System.out.println("        未排入: "
                + u.unit.specL + "x" + u.unit.specW + " (" + u.reason + ")"));
    }

    static final int L = 2440, W = 1830, KERF = 3;
    static final long DEADLINE = 400_000_000L;

    public static void main(String[] args) {
        // 场景1 种子清单
        {
            List<Unit> u = expand(List.of(new Spec(1200, 900, 2), new Spec(800, 600, 3)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, KERF);
            Eval b = eval(PackingEngine.search("REMNANT", u, L, W, KERF, 0, 12_000_000), u, L, W, KERF);
            dump("A1", a); dump("B1", b);
            check("S1 两策略放全", a.run().placed.size() == 5 && b.run().placed.size() == 5);
            check("S1 B整块短边>=A", b.minSide() >= a.minSide() - 1e-6);
        }

        // 场景3 面积够几何不够
        {
            List<Unit> u = expand(List.of(new Spec(1300, 1300, 2)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, KERF);
            Eval b = eval(PackingEngine.search("REMNANT", u, L, W, KERF, 0, 12_000_000), u, L, W, KERF);
            dump("A3", a); dump("B3", b);
            check("S3 总面积够也不算成功", a.run().placed.size() < 2 && b.run().placed.size() < 2);
        }

        // 场景5 禁止旋转
        {
            List<Unit> u = expand(List.of(new Spec(1300, 2400, 1)), false);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, KERF);
            Eval b = eval(PackingEngine.search("REMNANT", u, L, W, KERF, 0, 12_000_000), u, L, W, KERF);
            check("S5 禁旋全失败", a.run().placed.size() == 0 && b.run().placed.size() == 0);
            check("S5 未排入原因NO_FIT",
                    a.run().unplaced.stream().allMatch(x -> "NO_FIT".equals(x.reason)));
        }

        // 场景4 必须旋转才能放
        {
            List<Unit> u = expand(List.of(new Spec(1300, 2400, 1)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, KERF);
            Eval b = eval(PackingEngine.search("REMNANT", u, L, W, KERF, 0, 12_000_000), u, L, W, KERF);
            check("S4 旋转可放", a.run().placed.size() == 1 && b.run().placed.size() == 1);
            check("S4 确实旋转", a.run().placed.get(0).rotated && b.run().placed.get(0).rotated);
        }

        // 搜索预算耗尽：未排入必须覆盖剩余全部片
        {
            List<Unit> u = expand(List.of(new Spec(600, 400, 8)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, 0, 50), u, L, W, KERF);
            check("S10 超时标记", a.run().timedOut);
            check("S10 未排入覆盖全部", a.run().placed.size() + a.run().unplaced.size() == 8);
            check("S10 未排入原因是LIMITED",
                    a.run().unplaced.stream().allMatch(x -> "LIMITED".equals(x.reason)));
        }

        // 总面积超
        {
            List<Unit> u = expand(List.of(new Spec(1200, 1000, 4)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, KERF);
            Eval b = eval(PackingEngine.search("REMNANT", u, L, W, KERF, 0, 12_000_000), u, L, W, KERF);
            dump("A6", a); dump("B6", b);
            check("S6 面积超必有未排入", a.run().placed.size() < 4 && b.run().placed.size() < 4);
        }

        // 刀缝=0
        {
            List<Unit> u = expand(List.of(new Spec(1200, 900, 2), new Spec(800, 600, 3)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, 0, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, 0);
            check("S8 零刀缝放全", a.run().placed.size() == 5);
        }

        // 小料多片（性能/超时边界）
        {
            List<Unit> u = expand(List.of(new Spec(600, 400, 16)), true);
            Eval a = eval(PackingEngine.search("UTILIZATION", u, L, W, KERF, System.nanoTime() + DEADLINE, 12_000_000), u, L, W, KERF);
            Eval b = eval(PackingEngine.search("REMNANT", u, L, W, KERF, 0, 12_000_000), u, L, W, KERF);
            dump("A7", a); dump("B7", b);
            check("S7 16片放全", a.run().placed.size() == 16 && b.run().placed.size() == 16);
        }

        System.out.println(failures == 0 ? "\nJava 引擎断言：全部通过" : "\n失败 " + failures + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }
}
