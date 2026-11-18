package com.lumidrop.x.transfer

/**
 * ── GOAT-PASS 14 (Phase 1, §1.4): the ONE mapping from a queue-batch's ───────
 * PreviewItems to its archive filename — shared by the browser Quick-Share leg
 * (LumiDropServer.addZipBatchToQuickShare) and the native-push leg
 * (NativePushClient's zip-mode manifest / parked "Waiting" row), so both legs
 * of the very same send always agree on the name the receiver will see.
 *
 * The label rules mirror the established browser-side mapping: folders keep
 * their whole name, text items contribute their preview title, and loose
 * files contribute their title (extension stripped) in multi-item labels. The
 * final name — caps, "_more", and the true top-level count — comes from
 * [ZipNames], the same generator the browser's own "Download as zip" uses.
 */
fun zipBatchFileName(items: List<PreviewItem>): String =
    ZipNames.build(items.map { item ->
        ZipNames.Entry(
            when {
                item.isFolder -> item.name
                item.textContent != null -> item.name
                else -> item.name.substringBeforeLast('.').ifEmpty { item.name }
            },
            item.isFolder)
    })

/** GOAT-PASS 14 (§1.3): the batch entries that can actually contribute to an
 *  archive — every text item plus every item that still carries an address.
 *  Items with neither were never sendable and must not ride the filename's
 *  count nor fail the whole send. (Kept beside the naming helper so the
 *  filter and the name can never disagree about what the batch contains.) */
fun usableZipBatchItems(items: List<PreviewItem>): List<PreviewItem> =
    items.filter { it.textContent != null || it.isFolder || !it.path.isNullOrEmpty() || !it.uri.isNullOrEmpty() }
