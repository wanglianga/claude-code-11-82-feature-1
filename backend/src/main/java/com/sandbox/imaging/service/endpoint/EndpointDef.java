package com.sandbox.imaging.service.endpoint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 端点定义册的可执行解析模型。由 definitionJson 经 Jackson 反序列化得到，
 * 窗口、匹配策略、裁定优先级与重分类门限均为预登记内容，参与 specHash。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EndpointDef {
    public String code;
    public String name;
    /** 锚点类型，通常 FIRST_DIAGNOSIS */
    public String anchor;
    public int version = 1;
    /** 重分类门限（比例），超过则必须输出桥接报告而非静默复跑 */
    public double reclassificationThreshold = 0.10;
    public List<ComponentSpec> components = new ArrayList<>();
    /** 事件类型 -> 窗口标签优先级（索引 0 最高），同一证据命中两窗口时据此预登记裁定 */
    public java.util.Map<String, List<String>> conflictPolicy = new java.util.TreeMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WindowSpec {
        public String label;
        /** 参照组件类型：DIAGNOSIS / TREATMENT */
        public String ref;
        public int beforeDays;
        public int afterDays;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ComponentSpec {
        public String code;
        /** DIAGNOSIS / TREATMENT / IMAGING */
        public String type;
        public boolean requireConfirmed = true;
        public String modality;
        public String bodyPart;
        /** STRICT_ANCHOR（严格锚点窗口）/ PRIORITY（优先级匹配，候选全公开） */
        public String matchStrategy = "STRICT_ANCHOR";
        public WindowSpec window;
        public List<WindowSpec> windows = new ArrayList<>();

        public List<WindowSpec> effectiveWindows() {
            List<WindowSpec> all = new ArrayList<>(windows);
            if (window != null) all.add(window);
            return all;
        }
    }
}
