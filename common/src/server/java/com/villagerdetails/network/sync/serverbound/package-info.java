/**
 * C→S 的<b>服务端侧</b>封装（可选目录）。
 *
 * <p>一个 C→S 包正常只要两样东西，都不在这个目录里：
 * <ul>
 *     <li>包定义 → {@code network.sync.payload.serverbound}（负责注册包类型）</li>
 *     <li>收到包干什么 → {@code network.sync.receiver}</li>
 * </ul>
 * 客户端那边怎么发在 {@code com.villagerdetails.client.network.requests.ClientRequests}。
 *
 * <p>所以这里现在是空的：服务端既不发 C→S 包，也没有别的活要干。
 * 哪天真需要在服务端侧给某个 C→S 包加一层封装（比如收发一体的门面类），
 * 再按 {@code network.sync.clientbound} 的写法往这里放 {@code SyncChannel} 子类。
 */
package com.villagerdetails.network.sync.serverbound;
