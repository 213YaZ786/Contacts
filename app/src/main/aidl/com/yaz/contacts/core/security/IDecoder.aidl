package com.yaz.contacts.core.security;

/** Decodes an untrusted picture in an isolated process into plain pixels. */
interface IDecoder {
    ParcelFileDescriptor decode(in ParcelFileDescriptor input, int maxSide);
}
