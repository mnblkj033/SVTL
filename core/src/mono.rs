//! Mono 运行时交互：解析符号、定位托管方法、装钩子。
use crate::api::SvtlEvent;
use crate::host::{dispatch, log_line};
use std::ffi::{CString, CStr};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::{Mutex, OnceLock};

extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    fn dlerror() -> *mut c_char;
}

const RTLD_NOW: c_int = 2;

type FnDomainGet = unsafe extern "C" fn() -> *mut c_void;
type FnClassFromName = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> *mut c_void;
type FnGetMethod = unsafe extern "C" fn(*mut c_void, *const c_char, c_int) -> *mut c_void;
type FnCompile = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type FnAssemblyOpen = unsafe extern "C" fn(*const c_char, *mut *mut c_void) -> *mut c_void;
type FnAssemblyImage = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type FnRuntimeInvoke =
    unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_void, *mut *mut c_void) -> *mut c_void;
type FnClassGetField = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;
type FnFieldGetValue = unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_void);
type FnStringNew = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;
type FnThreadAttach = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type FnJitInit = unsafe extern "C" fn(*const c_char, *const c_char) -> *mut c_void;
type FnAssemblyLoaded = unsafe extern "C" fn(*const c_char) -> *mut c_void;
type FnStringToUtf8 = unsafe extern "C" fn(*mut c_void) -> *mut c_char;
type FnRootDomain = unsafe extern "C" fn() -> *mut c_void;
type FnAssemblyForeach = unsafe extern "C" fn(unsafe extern "C" fn(*mut c_void, *mut c_void), *mut c_void);
type FnImageGetName = unsafe extern "C" fn(*mut c_void) -> *const c_char;
type FnDomainAsmOpen = unsafe extern "C" fn(*mut c_void, *const c_char, *mut *mut c_void) -> *mut c_void;
type FnObjectUnbox = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type FnFree = unsafe extern "C" fn(*mut c_void);

pub struct MonoApi {
    pub base: *mut c_void,
    pub domain_get: Option<FnDomainGet>,
    pub class_from_name: Option<FnClassFromName>,
    pub get_method: Option<FnGetMethod>,
    pub compile: Option<FnCompile>,
    pub assembly_open: Option<FnAssemblyOpen>,
    pub assembly_image: Option<FnAssemblyImage>,
    pub runtime_invoke: Option<FnRuntimeInvoke>,
    pub class_get_field: Option<FnClassGetField>,
    pub field_get_value: Option<FnFieldGetValue>,
    pub string_new: Option<FnStringNew>,
    pub thread_attach: Option<FnThreadAttach>,
    pub jit_init: Option<FnJitInit>,
    pub domain_assembly_open: Option<FnDomainAsmOpen>,
    pub assembly_loaded: Option<FnAssemblyLoaded>,
    pub string_to_utf8: Option<FnStringToUtf8>,
    pub root_domain: Option<FnRootDomain>,
    pub assembly_foreach: Option<FnAssemblyForeach>,
    pub image_get_name: Option<FnImageGetName>,
    pub object_unbox: Option<FnObjectUnbox>,
    pub free: Option<FnFree>,
    pub domain: *mut c_void,
    pub image: *mut c_void,
}
unsafe impl Send for MonoApi {}

pub struct HookInfo {
    pub id: u64,
    pub type_name: String,
    pub method: String,
    pub target: usize,
    pub trampoline: usize,
}
unsafe impl Send for HookInfo {}

static MONO: OnceLock<Mutex<Option<MonoApi>>> = OnceLock::new();
static HOOKS: OnceLock<Mutex<Vec<HookInfo>>> = OnceLock::new();
static NEXT: Mutex<u64> = Mutex::new(1);

fn mono_cell() -> &'static Mutex<Option<MonoApi>> {
    MONO.get_or_init(|| Mutex::new(None))
}
fn hooks() -> &'static Mutex<Vec<HookInfo>> {
    HOOKS.get_or_init(|| Mutex::new(Vec::new()))
}

fn sym(base: *mut c_void, name: &str) -> *mut c_void {
    let c = CString::new(name).unwrap_or_default();
    unsafe { dlsym(base, c.as_ptr()) }
}

fn cast<T>(p: *mut c_void) -> Option<T> {
    if p.is_null() {
        None
    } else {
        Some(unsafe { std::mem::transmute_copy::<*mut c_void, T>(&p) })
    }
}

/// 打开 Mono 运行时、解析符号、打开游戏主程序集。返回是否成功。
pub fn resolve() -> bool {
    resolve_with("libmonosgen-2.0.so")
}

/// 用指定路径打开 Mono 运行时（安卓上 System.load 之后按绝对路径打开）。
pub fn resolve_with(path: &str) -> bool {
    let mut cell = match mono_cell().lock() {
        Ok(c) => c,
        Err(_) => return false,
    };
    if cell.is_some() {
        return true;
    }
    let name = CString::new(path).unwrap();
    let base = unsafe { dlopen(name.as_ptr(), RTLD_NOW) };
    if base.is_null() {
        let err = unsafe {
            let e = dlerror();
            if e.is_null() {
                String::new()
            } else {
                CStr::from_ptr(e).to_string_lossy().into_owned()
            }
        };
        log_line(&format!("[mono] dlopen {path} 失败：{err}"));
        return false;
    }
    let api = MonoApi {
        base,
        domain_get: cast(sym(base, "mono_domain_get")),
        class_from_name: cast(sym(base, "mono_class_from_name")),
        get_method: cast(sym(base, "mono_class_get_method_from_name")),
        compile: cast(sym(base, "mono_compile_method")),
        assembly_open: cast(sym(base, "mono_assembly_open")),
        assembly_image: cast(sym(base, "mono_assembly_get_image")),
        runtime_invoke: cast(sym(base, "mono_runtime_invoke")),
        class_get_field: cast(sym(base, "mono_class_get_field_from_name")),
        field_get_value: cast(sym(base, "mono_field_get_value")),
        string_new: cast(sym(base, "mono_string_new")),
        thread_attach: cast(sym(base, "mono_thread_attach")),
        jit_init: cast(sym(base, "mono_jit_init_version")),
        domain_assembly_open: cast(sym(base, "mono_domain_assembly_open")),
        assembly_loaded: cast(sym(base, "mono_assembly_loaded")),
        string_to_utf8: cast(sym(base, "mono_string_to_utf8")),
        root_domain: cast(sym(base, "mono_get_root_domain")),
        assembly_foreach: cast(sym(base, "mono_assembly_foreach")),
        image_get_name: cast(sym(base, "mono_image_get_name")),
        object_unbox: cast(sym(base, "mono_object_unbox")),
        free: cast(sym(base, "mono_free")),
        domain: std::ptr::null_mut(),
        image: std::ptr::null_mut(),
    };
    let ready = api.image.is_null() == false && api.compile.is_some() && api.get_method.is_some();
    log_line(&format!(
        "[mono] 符号解析完成，主程序集{}",
        if api.image.is_null() { "未打开" } else { "已打开" }
    ));
    *cell = Some(api);
    if let Ok(mut c) = crate::host::core().lock() {
        c.mono_ready = ready;
    }
    ready
}

/// 定位托管方法，返回 AOT 原生地址。
pub fn find_method(type_name: &str, method: &str) -> Option<usize> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    if api.image.is_null() {
        return None;
    }
    let (ns, cls) = match type_name.rsplit_once('.') {
        Some((a, b)) => (a, b),
        None => ("", type_name),
    };
    let c_ns = CString::new(ns).ok()?;
    let c_cls = CString::new(cls).ok()?;
    let klass = unsafe { (api.class_from_name?)(api.image, c_ns.as_ptr(), c_cls.as_ptr()) };
    if klass.is_null() {
        log_line(&format!("[mono] 找不到类型 {type_name}"));
        return None;
    }
    let c_m = CString::new(method).ok()?;
    let m = unsafe { (api.get_method?)(klass, c_m.as_ptr(), -1) };
    if m.is_null() {
        log_line(&format!("[mono] 找不到方法 {type_name}.{method}"));
        return None;
    }
    let ptr = unsafe { (api.compile?)(m) };
    if ptr.is_null() {
        None
    } else {
        Some(ptr as usize)
    }
}

/// 装钩子：定位 → inline hook。返回 hook id（0 表示失败）。
pub fn request_hook(type_name: &str, method: &str, replacement: *mut c_void) -> u64 {
    let target = match find_method(type_name, method) {
        Some(t) => t,
        None => {
            log_line(&format!("[hook] {type_name}.{method} 定位失败"));
            return 0;
        }
    };
    match unsafe { crate::hook::install(target, replacement as usize) } {
        Ok(tramp) => {
            let id = {
                let mut n = NEXT.lock().unwrap_or_else(|e| e.into_inner());
                let id = *n;
                *n += 1;
                id
            };
            log_line(&format!(
                "[hook] #{id} {type_name}.{method} @ {target:#x} 已挂钩"
            ));
            if let Ok(mut v) = hooks().lock() {
                v.push(HookInfo {
                    id,
                    type_name: type_name.to_string(),
                    method: method.to_string(),
                    target,
                    trampoline: tramp as usize,
                });
            }
            id
        }
        Err(e) => {
            log_line(&format!("[hook] {type_name}.{method} 挂钩失败：{e}"));
            0
        }
    }
}

pub fn remove_hook(id: u64) {
    if let Ok(mut v) = hooks().lock() {
        v.retain(|h| h.id != id);
    }
}

pub fn hook_count() -> usize {
    hooks().lock().map(|v| v.len()).unwrap_or(0)
}

/// 给游戏线程发事件（供钩子回调使用）。
pub fn emit(ev: SvtlEvent, json: &str) {
    dispatch(ev, json);
}

/// 保证当前线程已挂到 Mono（从 JNI 线程回调时必须调）。
pub fn ensure_thread() {
    if let Ok(cell) = mono_cell().lock() {
        if let Some(api) = cell.as_ref() {
            if let (Some(attach), Some(root)) = (api.thread_attach, api.root_domain) {
                unsafe {
                    let d = root();
                    if !d.is_null() {
                        attach(d);
                    }
                }
            }
        }
    }
}

/// 运行时是否已经起来（root domain 存在）。
pub fn root_ready() -> bool {
    let cell = match mono_cell().lock() {
        Ok(c) => c,
        Err(_) => return false,
    };
    let api = match cell.as_ref() {
        Some(a) => a,
        None => return false,
    };
    match api.root_domain {
        Some(f) => !unsafe { f() }.is_null(),
        None => false,
    }
}

/// 取类型的静态字段值（返回对象指针）。
pub fn static_field_object(type_name: &str, field: &str) -> Option<*mut c_void> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    if api.image.is_null() {
        return None;
    }
    let (ns, cls) = type_name.rsplit_once('.').unwrap_or(("", type_name));
    let c_ns = CString::new(ns).ok()?;
    let c_cls = CString::new(cls).ok()?;
    let klass = unsafe { (api.class_from_name?)(api.image, c_ns.as_ptr(), c_cls.as_ptr()) };
    if klass.is_null() {
        return None;
    }
    let c_field = CString::new(field).ok()?;
    let f = unsafe { (api.class_get_field?)(klass, c_field.as_ptr()) };
    if f.is_null() {
        return None;
    }
    let mut out: *mut c_void = std::ptr::null_mut();
    unsafe { (api.field_get_value?)(klass, f, &mut out as *mut _ as *mut c_void) };
    Some(out)
}

/// 调一个托管方法（无参或简单参数由调用方拼好）。
pub unsafe fn invoke(method: *mut c_void, obj: *mut c_void, args: *mut *mut c_void) -> *mut c_void {
    let cell = match mono_cell().lock() {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let api = match cell.as_ref() {
        Some(a) => a,
        None => return std::ptr::null_mut(),
    };
    match api.runtime_invoke {
        Some(f) => f(method, obj, args as *mut c_void, std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// 找实例/静态方法（按名字和参数个数）。
pub fn find_method_of(type_name: &str, method: &str, params: i32) -> Option<*mut c_void> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    if api.image.is_null() {
        return None;
    }
    let (ns, cls) = type_name.rsplit_once('.').unwrap_or(("", type_name));
    let c_ns = CString::new(ns).ok()?;
    let c_cls = CString::new(cls).ok()?;
    let klass = unsafe { (api.class_from_name?)(api.image, c_ns.as_ptr(), c_cls.as_ptr()) };
    if klass.is_null() {
        return None;
    }
    let c_m = CString::new(method).ok()?;
    let m = unsafe { (api.get_method?)(klass, c_m.as_ptr(), params) };
    if m.is_null() {
        None
    } else {
        Some(m)
    }
}

/// 创建托管字符串。
pub fn new_string(s: &str) -> *mut c_void {
    let cell = match mono_cell().lock() {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let api = match cell.as_ref() {
        Some(a) => a,
        None => return std::ptr::null_mut(),
    };
    let (Some(new_str), Some(domain)) = (api.string_new, api.domain_get) else {
        return std::ptr::null_mut();
    };
    let c = match CString::new(s) {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    unsafe {
        let d = domain();
        if d.is_null() {
            return std::ptr::null_mut();
        }
        new_str(d, c.as_ptr())
    }
}

/// Mono 是否已经解析并打开主程序集。
pub fn is_ready() -> bool {
    match mono_cell().lock() {
        Ok(c) => c.as_ref().map(|a| !a.image.is_null()).unwrap_or(false),
        Err(_) => false,
    }
}

/// 初始化 Mono 运行时并打开游戏程序集（重宿主第一步）。
/// 成功后游戏程序集已加载到本进程，可以直接调托管方法。
pub fn boot(asm_dir: &str) -> bool {
    // 先按包内路径打开运行时（已打开会直接返回）
    let lib = format!("{asm_dir}/lib/arm64-v8a/libmonosgen-2.0.so");
    resolve_with(&lib);
    let mut cell = match mono_cell().lock() {
        Ok(c) => c,
        Err(_) => return false,
    };
    let api = match cell.as_mut() {
        Some(a) => a,
        None => return false,
    };
    if api.image.is_null() == false {
        log_line("[mono] 程序集已加载，跳过");
        return true;
    }
    let jit = match api.jit_init {
        Some(f) => f,
        None => {
            log_line("[mono] 缺少 mono_jit_init_version");
            return false;
        }
    };
    let ver = CString::new("v4.0.30319").unwrap();
    let domain = unsafe { jit(CString::new("svtl").unwrap().as_ptr(), ver.as_ptr()) };
    if domain.is_null() {
        log_line("[mono] mono_jit_init_version 失败");
        return false;
    }
    api.domain = domain;
    log_line("[mono] 运行时初始化完成");

    let open = match api.domain_assembly_open {
        Some(f) => f,
        None => return false,
    };
    let path = format!("{asm_dir}/assemblies/StardewValley.dll");
    let c_path = match CString::new(path.clone()) {
        Ok(c) => c,
        Err(_) => return false,
    };
    let mut status: *mut c_void = std::ptr::null_mut();
    let asm_ = unsafe { open(domain, c_path.as_ptr(), &mut status) };
    if asm_.is_null() {
        log_line(&format!("[mono] 打开 {path} 失败"));
        return false;
    }
    if let Some(image_of) = api.assembly_image {
        api.image = unsafe { image_of(asm_) };
    }
    if api.image.is_null() {
        log_line("[mono] 拿不到程序集 image");
        return false;
    }
    // 试读一个类型，确认元数据可用
    let ns = CString::new("StardewValley").unwrap();
    let cls = CString::new("Game1").unwrap();
    let klass = unsafe { (api.class_from_name.unwrap())(api.image, ns.as_ptr(), cls.as_ptr()) };
    if klass.is_null() {
        log_line("[mono] 程序集已打开，但找不到 StardewValley.Game1");
        return false;
    }
    log_line("[mono] StardewValley.Game1 已找到，程序集可用");
    true
}

/// 等游戏自己的运行时把程序集加载好，然后返回 image。
/// 用于合并包里：我们不初始化运行时，只借用游戏的。
pub fn wait_image(assembly: &str, timeout_ms: u64) -> Option<*mut c_void> {
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(timeout_ms);
    loop {
        if !root_ready() {
            if std::time::Instant::now() > deadline {
                return None;
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
            continue;
        }
        ensure_thread();

        let cell = match mono_cell().lock() {
            Ok(c) => c,
            Err(_) => return None,
        };
        if let Some(api) = cell.as_ref() {
            if let (Some(open), Some(image_of), Some(root)) =
                (api.domain_assembly_open, api.assembly_image, api.root_domain)
            {
                let domain = unsafe { root() };
                if !domain.is_null() {
                    if let Ok(c_name) = CString::new(assembly) {
                        let mut status: *mut c_void = std::ptr::null_mut();
                        let asm = unsafe { open(domain, c_name.as_ptr(), &mut status) };
                        if !asm.is_null() {
                            let img = unsafe { image_of(asm) };
                            if !img.is_null() {
                                return Some(img);
                            }
                        }
                    }
                }
            }
        }
        if std::time::Instant::now() > deadline {
            return None;
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}

/// 在指定 image 里找类型。
pub fn class_in(image: *mut c_void, ns: &str, name: &str) -> Option<*mut c_void> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let c_ns = CString::new(ns).ok()?;
    let c_name = CString::new(name).ok()?;
    let klass = unsafe { (api.class_from_name?)(image, c_ns.as_ptr(), c_name.as_ptr()) };
    if klass.is_null() { None } else { Some(klass) }
}

/// 类上有没有这个字段（用于启动时自检日志）。
pub fn has_field(klass: *mut c_void, name: &str) -> bool {
    let cell = match mono_cell().lock() {
        Ok(c) => c,
        Err(_) => return false,
    };
    let api = match cell.as_ref() {
        Some(a) => a,
        None => return false,
    };
    let (Some(get_field), Ok(c_name)) = (api.class_get_field, CString::new(name)) else {
        return false;
    };
    !unsafe { get_field(klass, c_name.as_ptr()) }.is_null()
}

/// 类上有没有这个 0 参方法。
pub fn has_method(klass: *mut c_void, name: &str) -> bool {
    method_of(klass, name).is_some()
}

/// 读静态字段（int）。
pub fn static_int(klass: *mut c_void, field: &str) -> Option<i32> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let c_field = CString::new(field).ok()?;
    let f = unsafe { (api.class_get_field?)(klass, c_field.as_ptr()) };
    if f.is_null() { return None; }
    let mut out: i32 = 0;
    unsafe { (api.field_get_value?)(klass, f, &mut out as *mut _ as *mut c_void) };
    Some(out)
}

/// 读静态字段（对象指针）。
pub fn static_object(klass: *mut c_void, field: &str) -> Option<*mut c_void> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let c_field = CString::new(field).ok()?;
    let f = unsafe { (api.class_get_field?)(klass, c_field.as_ptr()) };
    if f.is_null() { return None; }
    let mut out: *mut c_void = std::ptr::null_mut();
    unsafe { (api.field_get_value?)(klass, f, &mut out as *mut _ as *mut c_void) };
    if out.is_null() { None } else { Some(out) }
}

/// 调托管方法；带异常出参，托管异常不外抛（否则 Mono 会 abort 整个进程）。
fn invoke_checked(m: *mut c_void, obj: *mut c_void, args: *mut *mut c_void) -> Option<*mut c_void> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let f = api.runtime_invoke?;
    let mut exc: *mut c_void = std::ptr::null_mut();
    let r = unsafe { f(m, obj, args as *mut c_void, &mut exc) };
    if !exc.is_null() || r.is_null() {
        None
    } else {
        Some(r)
    }
}

/// 取方法（0 个参数）。
pub fn method_of(klass: *mut c_void, name: &str) -> Option<*mut c_void> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let c_m = CString::new(name).ok()?;
    let m = unsafe { (api.get_method?)(klass, c_m.as_ptr(), 0) };
    if m.is_null() { None } else { Some(m) }
}

/// 拆箱读 int（属性/方法返回的是装箱值，不能直接按指针读）。
fn unbox_int(r: *mut c_void) -> Option<i32> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let unbox = api.object_unbox?;
    let p = unsafe { unbox(r) };
    if p.is_null() { None } else { Some(unsafe { *(p as *const i32) }) }
}

fn unbox_bool(r: *mut c_void) -> Option<bool> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let unbox = api.object_unbox?;
    let p = unsafe { unbox(r) };
    if p.is_null() { None } else { Some(unsafe { *(p as *const u8) } != 0) }
}

/// 读静态属性的对象值（`Game1.player` 这类是属性不是字段）。
pub fn prop_object(klass: *mut c_void, prop: &str, obj: *mut c_void) -> Option<*mut c_void> {
    let m = method_of(klass, &format!("get_{prop}"))?;
    invoke_checked(m, obj, std::ptr::null_mut())
}

/// 读 int 属性。
pub fn prop_int(klass: *mut c_void, prop: &str, obj: *mut c_void) -> Option<i32> {
    unbox_int(prop_object(klass, prop, obj)?)
}

/// 读 string 属性（返回值是 MonoString*）。
pub fn prop_string(klass: *mut c_void, prop: &str, obj: *mut c_void) -> Option<String> {
    let r = prop_object(klass, prop, obj)?;
    to_string(r)
}

/// 调 0 参方法，返回 bool。
pub fn call_bool(klass: *mut c_void, method: &str, obj: *mut c_void) -> Option<bool> {
    let m = method_of(klass, method)?;
    unbox_bool(invoke_checked(m, obj, std::ptr::null_mut())?)
}

/// MonoString* → Rust String（读出来就释放，避免每秒几次的泄漏）。
fn to_string(r: *mut c_void) -> Option<String> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let conv = api.string_to_utf8?;
    let free = api.free;
    let p = unsafe { conv(r) };
    if p.is_null() {
        return None;
    }
    let out = unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() };
    if let Some(f) = free {
        unsafe { f(p as *mut c_void) };
    }
    Some(out)
}

/// 调无参 getter，返回 int。
pub fn invoke_int(obj: *mut c_void, klass: *mut c_void, method: &str) -> Option<i32> {
    let m = method_of(klass, method)?;
    unbox_int(invoke_checked(m, obj, std::ptr::null_mut())?)
}

/// 调无参 getter，返回字符串。
pub fn invoke_string(obj: *mut c_void, klass: *mut c_void, method: &str) -> Option<String> {
    let m = method_of(klass, method)?;
    to_string(invoke_checked(m, obj, std::ptr::null_mut())?)
}

/// 尝试打开 Mono 运行时（已打开则直接返回 true）。
pub fn try_resolve() -> bool {
    if is_ready_lib() {
        return true;
    }
    resolve_with("libmonosgen-2.0.so");
    is_ready_lib()
}

fn is_ready_lib() -> bool {
    match mono_cell().lock() {
        Ok(c) => c.is_some(),
        Err(_) => false,
    }
}

static mut SCAN_GET_IMAGE: Option<FnAssemblyImage> = None;
static mut SCAN_GET_NAME: Option<FnImageGetName> = None;

struct ScanCtx {
    found: *mut c_void,
    names: Vec<String>,
}

unsafe extern "C" fn scan_assembly(asm: *mut c_void, user: *mut c_void) {
    if asm.is_null() || user.is_null() {
        return;
    }
    let ctx = &mut *(user as *mut ScanCtx);
    let (Some(get_image), Some(get_name)) = (SCAN_GET_IMAGE, SCAN_GET_NAME) else {
        return;
    };
    let img = get_image(asm);
    if img.is_null() {
        return;
    }
    let n = get_name(img);
    if n.is_null() {
        return;
    }
    let name = std::ffi::CStr::from_ptr(n).to_string_lossy().into_owned();
    if name.contains("StardewValley") && !name.contains("GameData") && ctx.found.is_null() {
        ctx.found = img;
    }
    ctx.names.push(name);
}

/// 遍历已加载的程序集，找 StardewValley 的 image（最稳的方式）。
/// 返回 (image 或 null, 已加载程序集名字)，名字用于日志排查。
pub fn scan_assemblies() -> Option<(*mut c_void, Vec<String>)> {
    let cell = mono_cell().lock().ok()?;
    let api = cell.as_ref()?;
    let foreach = api.assembly_foreach?;
    unsafe {
        SCAN_GET_IMAGE = api.assembly_image;
        SCAN_GET_NAME = api.image_get_name;
        let mut ctx = ScanCtx {
            found: std::ptr::null_mut(),
            names: Vec::new(),
        };
        foreach(scan_assembly, &mut ctx as *mut ScanCtx as *mut c_void);
        let ScanCtx { found, names } = ctx;
        Some((found, names))
    }
}

/// 找已加载的游戏 image。
pub fn find_loaded_image() -> Option<*mut c_void> {
    match scan_assemblies() {
        Some((img, _)) if !img.is_null() => Some(img),
        _ => None,
    }
}

/// 等游戏把程序集加载好，再遍历找出来。
pub fn wait_loaded_image(timeout_ms: u64) -> Option<*mut c_void> {
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(timeout_ms);
    let mut logged = false;
    loop {
        if root_ready() {
            ensure_thread();
            if let Some((img, names)) = scan_assemblies() {
                if !img.is_null() {
                    let hit = names
                        .iter()
                        .find(|n| n.contains("StardewValley") && !n.contains("GameData"))
                        .cloned()
                        .unwrap_or_default();
                    log_line(&format!(
                        "[mono] 已加载程序集 {} 个，匹配到 {}",
                        names.len(),
                        hit
                    ));
                    return Some(img);
                }
                if !logged && !names.is_empty() {
                    log_line(&format!(
                        "[mono] 还没匹配到游戏程序集，当前已加载 {} 个：{}",
                        names.len(),
                        names.join(", ")
                    ));
                    logged = true;
                }
            }
        }
        if std::time::Instant::now() > deadline {
            return None;
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
