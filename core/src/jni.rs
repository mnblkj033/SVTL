//! 极简 JNI 胶水：只做字符串互转。
//! JNIEnv 函数表固定顺序：167 NewStringUTF / 169 GetStringUTFChars / 170 ReleaseStringUTFChars
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};

type Table = *const *const c_void;

#[inline]
unsafe fn table(env: *mut c_void) -> Table {
    *(env as *mut Table)
}

pub unsafe fn string_of(env: *mut c_void, s: *mut c_void) -> Option<String> {
    if s.is_null() || env.is_null() {
        return None;
    }
    let t = table(env);
    let get: unsafe extern "C" fn(*mut c_void, *mut c_void, *mut u8) -> *const c_char =
        std::mem::transmute(*t.add(169));
    let release: unsafe extern "C" fn(*mut c_void, *mut c_void, *const c_char) =
        std::mem::transmute(*t.add(170));
    let p = get(env, s, std::ptr::null_mut());
    if p.is_null() {
        return None;
    }
    let out = CStr::from_ptr(p).to_string_lossy().into_owned();
    release(env, s, p);
    Some(out)
}

pub unsafe fn new_string(env: *mut c_void, s: &str) -> *mut c_void {
    if env.is_null() {
        return std::ptr::null_mut();
    }
    let t = table(env);
    let new: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void =
        std::mem::transmute(*t.add(167));
    let c = CString::new(s).unwrap_or_default();
    new(env, c.as_ptr())
}
