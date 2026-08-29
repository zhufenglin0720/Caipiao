package com.zfl.caipiao.utils;

import com.zfl.caipiao.cache.HmCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 去掉与上一期完全一致的预测号码：直选票按精确号剔除，定位码按各位集合比对后轮换。
 */
final class PrevPeriodDedup {

    private PrevPeriodDedup() {
    }

    static String lastField(List<HmCache.CompareDto> compares,
                            Function<HmCache.CompareDto, String> getter) {
        if (compares == null || compares.isEmpty() || getter == null) {
            return "";
        }
        for (int i = compares.size() - 1; i >= 0; i--) {
            HmCache.CompareDto dto = compares.get(i);
            if (dto == null) {
                continue;
            }
            String v = getter.apply(dto);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    static Set<String> ticketSet(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String raw : csv.split(",")) {
            String t = pad3(raw.trim());
            if (t.length() == 3) {
                out.add(t);
            }
        }
        return out;
    }

    static boolean sameTicketSet(String a, String b) {
        Set<String> x = ticketSet(a);
        Set<String> y = ticketSet(b);
        return !x.isEmpty() && x.equals(y);
    }

    static boolean sameIntSet(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int[] x = Arrays.copyOf(a, a.length);
        int[] y = Arrays.copyOf(b, b.length);
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    /**
     * 去掉上期原号后，用 extras 补到 cap；若仍与上期集合相同则再换 1 注。
     */
    static List<String> excludeTickets(List<String> next, Set<String> banned, int cap,
                                       List<String> extras) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (next != null) {
            for (String raw : next) {
                String t = pad3(raw);
                if (t.length() != 3 || (banned != null && banned.contains(t))) {
                    continue;
                }
                out.add(t);
                if (out.size() >= cap) {
                    break;
                }
            }
        }
        if (extras != null && out.size() < cap) {
            for (String raw : extras) {
                String t = pad3(raw);
                if (t.length() != 3 || (banned != null && banned.contains(t))) {
                    continue;
                }
                out.add(t);
                if (out.size() >= cap) {
                    break;
                }
            }
        }
        if (banned != null && !banned.isEmpty() && out.equals(banned) && extras != null) {
            for (String raw : extras) {
                String t = pad3(raw);
                if (t.length() == 3 && !banned.contains(t)) {
                    if (!out.isEmpty()) {
                        String first = out.iterator().next();
                        out.remove(first);
                    }
                    out.add(t);
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    static String joinTickets(List<String> tickets) {
        return tickets == null ? "" : String.join(",", tickets);
    }

    static String pad3(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 3) {
            return t.substring(t.length() - 3);
        }
        if (t.isEmpty()) {
            return "";
        }
        return "0".repeat(3 - t.length()) + t;
    }
}
