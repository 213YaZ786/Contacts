package com.yaz.contacts.navigation

import android.net.Uri

object Routes {
    const val LIST = "list"
    const val CONTACT = "contact/{id}"
    const val EDIT = "edit?id={id}&draft={draft}&me={me}"
    const val CHOOSE = "choose?draft={draft}"
    const val PICK = "pick/{kind}"
    const val IMPORT = "import?uri={uri}&person={person}"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val TIDY = "tidy"
    const val LABEL = "label/{id}"
    const val UNDO = "undo"
    const val SCAN = "scan"
    const val CARD = "card"
    const val POSTER = "poster/{id}"
    const val TAP = "tap?give={give}&id={id}"
    const val PRIVATE = "private/{id}"

    fun private(id: String) = "private/$id"

    /** Touch phones: gives the user's card, or the contact [id]'s, and takes theirs. */
    fun tap(give: Boolean, id: Long = -1L) = "tap?give=$give&id=$id"

    fun poster(id: Long) = "poster/$id"

    fun contact(id: Long) = "contact/$id"

    /** The editor: a contact by [id], or a new one; [draft] holds what to fill in (see Drafts). */
    fun edit(id: Long? = null, draft: Long? = null, me: Boolean = false) = "edit?id=${id ?: -1}&draft=${draft ?: -1}&me=$me"

    /** Add to a contact, new or existing: the person to add it to is chosen first. */
    fun choose(draft: Long) = "choose?draft=$draft"

    fun pick(kind: String) = "pick/$kind"
    fun import(uri: Uri) = "import?uri=" + Uri.encode(uri.toString())
    fun label(id: Long) = "label/$id"
}
