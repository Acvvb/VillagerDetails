/**
 * 兜底目录：几个对不上号的同步注册写在这里。
 *
 * <p>放 {@link com.villagerdetails.network.channel.SyncRegistrar} 的实现，扫到就调它的
 * {@code register()}，具体注册什么由类自己写。
 *
 * <p>目前是空目录（预留位）。放之前先对一遍：
 * <ul>
 *     <li>包定义 → {@code network.sync.payload.clientbound} / {@code payload.serverbound}</li>
 *     <li>发包 API → {@code network.sync.clientbound}</li>
 *     <li>服务端收包 → {@code network.sync.receiver}</li>
 *     <li>事件接线 → {@code network.sync.event}</li>
 * </ul>
 * 能和上面某一个对上号就别放这儿。
 */
package com.villagerdetails.network.sync.other;
