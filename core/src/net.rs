//! 网络抓包：钩 Lidgren 的收发方法，把原始报文转成事件。
//!
//! 只读，不改报文内容。
//! 托管 byte[] 的内存布局（64 位）：
//!   +0  vtable      +8  bounds      +16 长度      +24 数据
use crate::api::*;
use crate::host::dispatch;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicUsize, Ordering};

static ORIG_SEND: AtomicUsize = AtomicUsize::new(0);
static ORIG_RECV: AtomicUsize = AtomicUsize::new(0);

type SendFn = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_void);
type RecvFn = unsafe extern "C" fn(*mut c_void);

/// 从托管 byte[] 指针读长度和内容（最多 32 字节转 hex）。
pub unsafe fn read_array(p: *mut c_void) -> Option<(usize, String)> {
    if p.is_null() {
        return None;
    }
    let base = p as *const usize;
    let len = *base.add(2);
    if len == 0 || len > (1 << 20) {
        return None;
    }
    let data = (p as *const u8).add(24);
    let n = len.min(32);
    let mut hex = String::with_capacity(n * 2);
    for i in 0..n {
        hex.push_str(&format!("{:02x}", *data.add(i)));
    }
    Some((len, hex))
}

fn emit(ev: SvtlEvent, direction: &str, p: *mut c_void) {
    let (len, hex) = match unsafe { read_array(p) } {
        Some(v) => v,
        None => return,
    };
    dispatch(
        ev,
        &format!("{{\"dir\":\"{direction}\",\"len\":{len},\"hex\":\"{hex}\"}}"),
    );
}

/// LidgrenServer.sendMessage(NetConnection, byte[])
pub unsafe extern "C" fn on_server_send(this: *mut c_void, conn: *mut c_void, data: *mut c_void) {
    let orig = ORIG_SEND.load(Ordering::Acquire);
    emit(EV_PACKET_OUT, "server->client", data);
    if orig != 0 {
        let f: SendFn = std::mem::transmute(orig);
        f(this, conn, data);
    }
}

/// LidgrenServer.receiveMessages()
pub unsafe extern "C" fn on_server_recv(this: *mut c_void) {
    let orig = ORIG_RECV.load(Ordering::Acquire);
    if orig != 0 {
        let f: RecvFn = std::mem::transmute(orig);
        f(this);
    }
    dispatch(EV_PACKET_IN, "{\"dir\":\"client->server\",\"note\":\"receiveMessages\"}");
}

pub fn store_send(tramp: usize) {
    ORIG_SEND.store(tramp, Ordering::Release);
}
pub fn store_recv(tramp: usize) {
    ORIG_RECV.store(tramp, Ordering::Release);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_managed_array() {
        // 伪造一个 byte[] 布局：16 字节头 + 长度 + 数据
        let mut buf = vec![0u8; 24 + 4];
        unsafe {
            let len_ptr = buf.as_mut_ptr().add(16) as *mut usize;
            *len_ptr = 4;
        }
        buf[24..28].copy_from_slice(&[0xde, 0xad, 0xbe, 0xef]);
        let p = buf.as_mut_ptr() as *mut c_void;
        let (len, hex) = unsafe { read_array(p) }.expect("解析失败");
        assert_eq!(len, 4);
        assert_eq!(hex, "deadbeef");
    }

    #[test]
    fn rejects_null_and_absurd_length() {
        assert!(unsafe { read_array(std::ptr::null_mut()) }.is_none());
        let mut buf = vec![0u8; 32];
        unsafe { *(buf.as_mut_ptr().add(16) as *mut usize) = 1 << 30 };
        assert!(unsafe { read_array(buf.as_mut_ptr() as *mut c_void) }.is_none());
    }
}
