package dev.chunkplan.common;

import java.util.UUID;

/**
 * 扣费事件日志抽象：壳层可注入文件实现（独立日志文件，不污染默认日志）。
 */
public interface FeeLogger {

    /**
     * @param uuid     玩家
     * @param dimKey   维度 key
     * @param chunkKey 打包后的区块坐标
     * @param speed    本 tick 位移（格/tick）
     * @param fee      本次扣费点数
     * @param total    该玩家累计总点数
     */
    void logFee(UUID uuid, String dimKey, long chunkKey, double speed, double fee, double total);
}
