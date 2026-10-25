package com.villagerdetails.config.impl.section.item;

import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.villagerdetails.rule.type.RuleType.PLAYER_HEAD_SITTING;

/**
 * 「玩家头顶乘坐」的<b>服务端</b>细项（每个存档一份，落在
 * {@code <world>/villagerdetails/config.json} 的 {@code PlayerHeadSitting} 节里）。
 *
 * <pre>
 * "PlayerHeadSitting": {
 *   "maxStackDepth": 2,
 *   "allowCreative": true,
 *   "dismountOnTargetRide": true
 * }
 * </pre>
 *
 * <p>规则本身只有一开一关（{@code /ec PlayerHeadSitting true}）。写死、没有开关的行为：
 * <ul>
 *     <li><b>旁观者一律不参与</b>（发起者和目标都算）；</li>
 *     <li><b>目标潜行 / 睡觉 / 换维度 → 一定把人踢下来</b>；</li>
 *     <li>目标头上已经有人、发起者已在骑东西、目标已死亡 → 坐不上去；</li>
 *     <li>乘客一律摆在「载具碰撞箱顶」，两个碰撞箱相切不重叠，没有高度配置。</li>
 * </ul>
 *
 * <p><b>「允许被骑」不在这个文件里</b>：那是每个玩家自己的客户端开关
 * （客户端配置「允许别人坐我头上」），由客户端发给服务端；<b>没装本 mod 的玩家算不允许</b>。
 *
 * <p>缺键时自动写回默认值（和 {@code FurnaceDirtyHighlightConfig} / {@code ServerLangConfig} 同一个套路）；
 * 改了值走 {@code /ec c reload} 生效，不用重启。
 */
public final class PlayerHeadSittingConfig implements ReloadableConfig {

    public static final PlayerHeadSittingConfig INSTANCE = new PlayerHeadSittingConfig();

    private PlayerHeadSittingConfig() {
    }

    private static final Logger LOGGER = LogManager.getLogger(PlayerHeadSittingConfig.class);

    // ==============================================================
    // 键名
    // ==============================================================

    private static final String KEY_MAX_STACK_DEPTH = "maxStackDepth";
    private static final String KEY_ALLOW_CREATIVE = "allowCreative";
    private static final String KEY_DISMOUNT_ON_TARGET_RIDE = "dismountOnTargetRide";

    // ==============================================================
    // 默认值
    // ==============================================================

    public static final int DEFAULT_MAX_STACK_DEPTH = 2;
    public static final boolean DEFAULT_ALLOW_CREATIVE = true;
    public static final boolean DEFAULT_DISMOUNT_ON_TARGET_RIDE = true;

    /**
     * 叠罗汉上限的下界：{@code 2} = 一个骑一个（也就是「能叠」这件事的最小形态）。
     * 填 1 等于谁都不许骑，没有意义，夹到 2。
     */
    private static final int MIN_STACK_DEPTH = 2;

    /**
     * 叠罗汉上限的上界：留点余量给「玩梗」的服务器，再多就纯属自找麻烦
     */
    private static final int MAX_STACK_DEPTH = 8;

    // ==============================================================
    // 当前值（只在服务端主线程读写）
    // ==============================================================

    private static volatile int maxStackDepth = DEFAULT_MAX_STACK_DEPTH;
    private static volatile boolean allowCreative = DEFAULT_ALLOW_CREATIVE;
    private static volatile boolean dismountOnTargetRide = DEFAULT_DISMOUNT_ON_TARGET_RIDE;

    @Override
    public String configName() {
        return PLAYER_HEAD_SITTING.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        SectionReader reader = new SectionReader(UnifiedConfigFile.getSection(configName()));

        maxStackDepth = reader.integer(KEY_MAX_STACK_DEPTH, DEFAULT_MAX_STACK_DEPTH, MIN_STACK_DEPTH, MAX_STACK_DEPTH);
        allowCreative = reader.bool(KEY_ALLOW_CREATIVE, DEFAULT_ALLOW_CREATIVE);
        dismountOnTargetRide = reader.bool(KEY_DISMOUNT_ON_TARGET_RIDE, DEFAULT_DISMOUNT_ON_TARGET_RIDE);

        if (reader.dirty()) {
            UnifiedConfigFile.save();
        }
        LOGGER.info("[{}] maxStackDepth={}, allowCreative={}, dismountOnTargetRide={}",
                configName(), maxStackDepth, allowCreative, dismountOnTargetRide);
    }

    /**
     * 一摞「人肉塔」最多能有几个玩家（含最底下那个），保证在合法区间内。
     *
     * <p>{@code 2}（默认）= 一个骑一个；{@code 3} = 允许再往上叠一个（A 坐在 B 头上，B 又坐在 C 头上）。
     *
     * <p>注意它<b>和 {@link #dismountOnTargetRide()} 是互相制约的</b>：那个开关默认开着，
     * 而它会把「自己变成乘客」的目标头上的乘客全部踢掉 —— 也就是说默认配置下塔最多只有两层高。
     * 想真的叠三层以上，得把 {@code dismountOnTargetRide} 改成 {@code false}。
     */
    public static int maxStackDepth() {
        return maxStackDepth;
    }

    /**
     * 创造模式的玩家能不能发起乘坐（只限制发起者；被坐的一方是创造模式无所谓）
     */
    public static boolean allowCreative() {
        return allowCreative;
    }

    /**
     * 目标上了别的载具（船 / 马 / 乐魂……）时是否踢下乘客。
     *
     * <p>检测的就是「目标自己变成了乘客」，所以它和 {@link #maxStackDepth()} 互相制约：
     * 想叠三层以上（目标头上有人、目标自己又骑在别人头上）必须把它关掉。
     */
    public static boolean dismountOnTargetRide() {
        return dismountOnTargetRide;
    }

    /**
     * 读一个配置节：缺键 / 类型不对就把默认值写回去，并把 {@code dirty} 置上。
     *
     * <p>数字一律夹到区间里再落盘，所以外面拿到的永远是合法值 —— 手改配置写了个
     * {@code 0} 或 {@code "abc"} 也不会把规则玩坏。
     */
    private static final class SectionReader {

        private final JsonObject section;
        private boolean dirty;

        SectionReader(JsonObject section) {
            this.section = section;
        }

        boolean dirty() {
            return dirty;
        }

        boolean bool(String key, boolean fallback) {
            if (isPrimitive(key) && section.getAsJsonPrimitive(key).isBoolean()) {
                return section.get(key).getAsBoolean();
            }
            section.addProperty(key, fallback);
            dirty = true;
            return fallback;
        }

        int integer(String key, int fallback, int min, int max) {
            if (isPrimitive(key) && section.getAsJsonPrimitive(key).isNumber()) {
                int raw = section.get(key).getAsInt();
                int clamped = Math.clamp(raw, min, max);
                if (clamped != raw) {
                    // 越界的值顺手改回合法值写盘（例如老配置里的 1 现在没意义了）
                    section.addProperty(key, clamped);
                    dirty = true;
                }
                return clamped;
            }
            section.addProperty(key, fallback);
            dirty = true;
            return fallback;
        }

        private boolean isPrimitive(String key) {
            return section.has(key) && section.get(key).isJsonPrimitive();
        }
    }
}
