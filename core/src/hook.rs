//! arm64 inline hook：改写函数头 16 字节，跳到我们的替换函数。
//!
//! 目标函数头会被写成：
//!   ldr x16, #8
//!   br  x16
//!   .quad <replacement>
//! 原 16 字节搬到 trampoline，再补一条跳回 target+16 的尾巴。
//!
//! 限制：前 4 条指令不能是 PC 相关的（adr/adrp/ldr literal/b/b.cond/cbz…），
//! 遇到就返回错误，由上层换别的方案。
use std::os::raw::{c_int, c_void};

const PAGE: usize = 4096;
const PROT_R: c_int = 1;
const PROT_W: c_int = 2;
const PROT_X: c_int = 4;
const MAP_PRIVATE: c_int = 2;
const MAP_ANON: c_int = 0x20;

extern "C" {
    fn mmap(addr: *mut c_void, len: usize, prot: c_int, flags: c_int, fd: c_int, off: i64)
        -> *mut c_void;
    fn mprotect(addr: *mut c_void, len: usize, prot: c_int) -> c_int;
}

/// 判断指令是否与 PC 相关（搬移后语义会变）。
fn pc_relative(insn: u32) -> bool {
    // ADR / ADRP
    if insn & 0x1F00_0000 == 0x1000_0000 {
        return true;
    }
    // B / BL
    if insn & 0x7C00_0000 == 0x1400_0000 {
        return true;
    }
    // B.cond
    if insn & 0xFF00_0010 == 0x5400_0000 {
        return true;
    }
    // CBZ / CBNZ
    if insn & 0x7E00_0000 == 0x3400_0000 {
        return true;
    }
    // LDR (literal)
    if insn & 0x3B00_0000 == 0x1800_0000 {
        return true;
    }
    // TBZ / TBNZ
    if insn & 0x7E00_0000 == 0x3600_0000 {
        return true;
    }
    false
}

fn page_of(addr: usize) -> usize {
    addr & !(PAGE - 1)
}

/// 安装钩子，返回 trampoline 地址（调用它等于调用原函数）。
pub unsafe fn install(target: usize, replacement: usize) -> Result<*mut c_void, String> {
    if target == 0 || replacement == 0 {
        return Err("地址为空".into());
    }
    if target % 4 != 0 {
        return Err(format!("目标未对齐: {target:#x}"));
    }
    let insns = std::slice::from_raw_parts(target as *const u32, 4);
    for (i, &insn) in insns.iter().enumerate() {
        if pc_relative(insn) {
            return Err(format!("第 {} 条指令是 PC 相关（{insn:#010x}），需要重定位", i + 1));
        }
    }

    let tramp = mmap(
        std::ptr::null_mut(),
        PAGE,
        PROT_R | PROT_W | PROT_X,
        MAP_PRIVATE | MAP_ANON,
        -1,
        0,
    );
    if tramp as isize == -1 {
        return Err("mmap trampoline 失败".into());
    }
    // 1) 原指令
    std::ptr::copy_nonoverlapping(target as *const u8, tramp as *mut u8, 16);
    // 2) 跳回 target + 16
    let tail = (tramp as *mut u8).add(16);
    write_jump(tail, target + 16);

    // 3) 改写目标函数头
    let page = page_of(target);
    if mprotect(page as *mut c_void, PAGE, PROT_R | PROT_W | PROT_X) != 0 {
        return Err("mprotect(RWX) 失败".into());
    }
    write_jump(target as *mut u8, replacement);
    mprotect(page as *mut c_void, PAGE, PROT_R | PROT_X);

    Ok(tramp)
}

unsafe fn write_jump(at: *mut u8, dest: usize) {
    let insn = at as *mut u32;
    // ldr x16, #8
    insn.write_unaligned(0x5800_0050);
    // br x16
    insn.add(1).write_unaligned(0xD61F_0200);
    // 64 位地址
    let addr = at.add(8) as *mut usize;
    addr.write_unaligned(dest);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[inline(never)]
    extern "C" fn target_fn(x: i64) -> i64 {
        // 保证够长，前 4 条指令不会被内联/优化掉
        let mut v = x * 2;
        v += 7;
        v ^= 3;
        v + 1
    }

    #[inline(never)]
    extern "C" fn replacement_fn(x: i64) -> i64 {
        x + 1000
    }

    #[test]
    fn hook_replaces_function() {
        assert_eq!(target_fn(5), (((5 * 2) + 7) ^ 3) + 1);
        let tramp = unsafe { install(target_fn as usize, replacement_fn as usize) }.expect("安装失败");
        assert_eq!(target_fn(5), 1005, "钩子没生效");
        // 通过 trampoline 调原函数
        let orig: extern "C" fn(i64) -> i64 = unsafe { std::mem::transmute(tramp) };
        assert_eq!(orig(5), (((5 * 2) + 7) ^ 3) + 1, "trampoline 不对");
    }
}
