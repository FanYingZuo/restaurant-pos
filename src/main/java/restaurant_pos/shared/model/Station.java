package restaurant_pos.shared.model;

/** 站別只有 K1、K2（D-11）。放在 shared，因為 menu、order、kds 都會用到，而 kds 不可依賴 menu（§4.1）。 */
public enum Station {
    K1,
    K2
}
