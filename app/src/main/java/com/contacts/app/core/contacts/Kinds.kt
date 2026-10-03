package com.contacts.app.core.contacts

import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website

/**
 * The kinds a row can be labelled with, worded by Android itself so they
 * read in the phone's language, and the user's own word when custom.
 */
enum class Field(val kinds: List<Int>, val custom: Int) {
    PHONE(listOf(Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK, Phone.TYPE_MAIN, Phone.TYPE_WORK_MOBILE, Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME, Phone.TYPE_PAGER, Phone.TYPE_OTHER), Phone.TYPE_CUSTOM),
    EMAIL(listOf(Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_MOBILE, Email.TYPE_OTHER), Email.TYPE_CUSTOM),
    POSTAL(listOf(StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK, StructuredPostal.TYPE_OTHER), StructuredPostal.TYPE_CUSTOM),
    EVENT(listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY, Event.TYPE_OTHER), Event.TYPE_CUSTOM),
    RELATION(
        listOf(
            Relation.TYPE_SPOUSE, Relation.TYPE_PARTNER, Relation.TYPE_CHILD, Relation.TYPE_MOTHER, Relation.TYPE_FATHER,
            Relation.TYPE_PARENT, Relation.TYPE_BROTHER, Relation.TYPE_SISTER, Relation.TYPE_FRIEND, Relation.TYPE_RELATIVE,
            Relation.TYPE_MANAGER, Relation.TYPE_ASSISTANT, Relation.TYPE_REFERRED_BY, Relation.TYPE_DOMESTIC_PARTNER
        ),
        Relation.TYPE_CUSTOM
    ),
    WEBSITE(listOf(Website.TYPE_HOMEPAGE, Website.TYPE_BLOG, Website.TYPE_PROFILE, Website.TYPE_HOME, Website.TYPE_WORK, Website.TYPE_OTHER), Website.TYPE_CUSTOM),
    SIP(listOf(SipAddress.TYPE_HOME, SipAddress.TYPE_WORK, SipAddress.TYPE_OTHER), SipAddress.TYPE_CUSTOM);

    /** The word for [kind], or [custom] when the user wrote their own. */
    fun label(res: Resources, kind: Int, custom: String?): String {
        if (kind == this.custom || kind == 0) return custom?.takeIf { it.isNotBlank() } ?: Phone.getTypeLabel(res, Phone.TYPE_OTHER, null).toString()
        return when (this) {
            PHONE -> Phone.getTypeLabel(res, kind, custom).toString()
            EMAIL -> Email.getTypeLabel(res, kind, custom).toString()
            POSTAL -> StructuredPostal.getTypeLabel(res, kind, custom).toString()
            EVENT -> res.getString(Event.getTypeResource(kind))
            RELATION -> Relation.getTypeLabel(res, kind, custom).toString()
            SIP -> SipAddress.getTypeLabel(res, kind, custom).toString()
            WEBSITE -> when (kind) {
                Website.TYPE_HOMEPAGE -> "Homepage"
                Website.TYPE_BLOG -> "Blog"
                Website.TYPE_PROFILE -> "Profile"
                Website.TYPE_HOME -> "Home"
                Website.TYPE_WORK -> "Work"
                Website.TYPE_FTP -> "FTP"
                else -> "Other"
            }
        }
    }
}
