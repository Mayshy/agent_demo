package askred.eval;

import java.util.List;

public record EvalQuery(String query, List<String> expectedAspects) {

    public static List<EvalQuery> preset() {
        return List.of(
            new EvalQuery("我想去巴厘岛玩5天，预算5000，喜欢拍照，一个人",
                List.of("推荐具体地点", "提及拍照场景", "预算不超5000", "适合单人")),
            new EvalQuery("大理和丽江哪个更适合情侣？",
                List.of("对比两地方", "提及情侣元素", "具体建议")),
            new EvalQuery("推荐一个适合带父母去的地方，不用走太多路",
                List.of("适合老人", "行程轻松", "有具体地点")),
            new EvalQuery("京都有什么必去的寺庙？",
                List.of("寺庙推荐", "具体信息", "实用建议")),
            new EvalQuery("曼谷哪里吃海鲜性价比高？",
                List.of("海鲜推荐", "价格参考", "具体地点"))
        );
    }
}
