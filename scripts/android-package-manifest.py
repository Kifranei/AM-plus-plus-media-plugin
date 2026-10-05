"""Read the identity from Android's binary XML manifest, using only the standard library."""
import struct
import xml.etree.ElementTree as ET

def manifest_identity(data):
    if data.lstrip().startswith(b'<'):
        attributes = ET.fromstring(data).attrib
        return (attributes['package'], attributes['{http://schemas.android.com/apk/res/android}versionName'],
                int(attributes['{http://schemas.android.com/apk/res/android}versionCode']))
    def u16(offset): return struct.unpack_from('<H',data,offset)[0]
    def u32(offset): return struct.unpack_from('<I',data,offset)[0]
    def length8(offset):
        first=data[offset]
        return (((first&127)<<8)|data[offset+1],offset+2) if first&128 else (first,offset+1)
    def length16(offset):
        first=u16(offset)
        return (((first&32767)<<16)|u16(offset+2),offset+4) if first&32768 else (first,offset+2)
    if u16(0)!=3: raise ValueError('Not Android binary XML')
    offset=u16(2);strings=[]
    while offset<len(data):
        kind=u16(offset);header=u16(offset+2);size=u32(offset+4)
        if size<header or offset+size>len(data): raise ValueError('Invalid XML chunk')
        if kind==1:
            count=u32(offset+8);utf8=bool(u32(offset+16)&256);start=offset+u32(offset+20)
            for index in range(count):
                cursor=start+u32(offset+header+index*4)
                if utf8:
                    _,cursor=length8(cursor);length,cursor=length8(cursor)
                    strings.append(data[cursor:cursor+length].decode('utf8'))
                else:
                    length,cursor=length16(cursor)
                    strings.append(data[cursor:cursor+length*2].decode('utf-16-le'))
        elif kind==0x102 and strings[u32(offset+20)]=='manifest':
            attributes={};start=offset+16+u16(offset+24);width=u16(offset+26);count=u16(offset+28)
            for index in range(count):
                cursor=start+index*width;name=strings[u32(cursor+4)];raw=u32(cursor+8)
                datatype=data[cursor+15];value=u32(cursor+16)
                attributes[name]=strings[raw] if raw!=0xffffffff else strings[value] if datatype==3 else value
            return (attributes['package'],attributes['versionName'],int(attributes['versionCode']))
        offset+=size
    raise ValueError('Missing manifest identity')
