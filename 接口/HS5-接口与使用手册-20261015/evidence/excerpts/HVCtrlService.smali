.class public Lfaw/system/viewservice/HVCtrlService;
.super Landroid/app/Service;
.source "HVCtrlService.java"


# annotations
.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lfaw/system/viewservice/HVCtrlService$IncomingHandler;
    }
.end annotation


# static fields
.field private static final TAG:Ljava/lang/String;


# instance fields
.field private hardKeyManagerServer:Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

.field private server:Landroid/os/Messenger;


# direct methods
.method static constructor <clinit>()V
    .registers 1

    .line 25
    const-class v0, Lfaw/system/viewservice/HVCtrlService;

    invoke-virtual {v0}, Ljava/lang/Class;->getSimpleName()Ljava/lang/String;

    move-result-object v0

    sput-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    return-void
.end method

.method public constructor <init>()V
    .registers 3

    .line 57
    invoke-direct {p0}, Landroid/app/Service;-><init>()V

    .line 58
    sget-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    invoke-direct {p0}, Lfaw/system/viewservice/HVCtrlService;->getMethodName()Ljava/lang/String;

    move-result-object v1

    invoke-static {v0, v1}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I

    .line 59
    return-void
.end method

.method static synthetic access$000(Lfaw/system/viewservice/HVCtrlService;)Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;
    .registers 2
    .param p0, "x0"    # Lfaw/system/viewservice/HVCtrlService;

    .line 24
    iget-object v0, p0, Lfaw/system/viewservice/HVCtrlService;->hardKeyManagerServer:Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

    return-object v0
.end method

.method private getMethodName()Ljava/lang/String;
    .registers 4

    .line 28
    invoke-static {}, Ljava/lang/Thread;->currentThread()Ljava/lang/Thread;

    move-result-object v0

    invoke-virtual {v0}, Ljava/lang/Thread;->getStackTrace()[Ljava/lang/StackTraceElement;

    move-result-object v0

    .line 29
    .local v0, "stacktrace":[Ljava/lang/StackTraceElement;
    const/4 v1, 0x3

    aget-object v1, v0, v1

    .line 30
    .local v1, "e":Ljava/lang/StackTraceElement;
    invoke-virtual {v1}, Ljava/lang/StackTraceElement;->getMethodName()Ljava/lang/String;

    move-result-object v2

    .line 31
    .local v2, "methodName":Ljava/lang/String;
    return-object v2
.end method


# virtual methods
.method public onBind(Landroid/content/Intent;)Landroid/os/IBinder;
    .registers 4
    .param p1, "intent"    # Landroid/content/Intent;

    .line 78
    sget-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    const-string v1, "onBind() --> IN"

    invoke-static {v0, v1}, Landroid/util/Log;->v(Ljava/lang/String;Ljava/lang/String;)I

    .line 79
    iget-object v0, p0, Lfaw/system/viewservice/HVCtrlService;->server:Landroid/os/Messenger;

    invoke-virtual {v0}, Landroid/os/Messenger;->getBinder()Landroid/os/IBinder;

    move-result-object v0

    return-object v0
.end method

.method public onCreate()V
    .registers 4

    .line 63
    invoke-super {p0}, Landroid/app/Service;->onCreate()V

    .line 65
    sget-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    invoke-direct {p0}, Lfaw/system/viewservice/HVCtrlService;->getMethodName()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v2, " --> IN"

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v0, v1}, Landroid/util/Log;->v(Ljava/lang/String;Ljava/lang/String;)I

    .line 67
    new-instance v0, Landroid/os/Messenger;

    new-instance v1, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;

    const/4 v2, 0x0

    invoke-direct {v1, p0, v2}, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;-><init>(Lfaw/system/viewservice/HVCtrlService;Lfaw/system/viewservice/HVCtrlService$1;)V

    invoke-direct {v0, v1}, Landroid/os/Messenger;-><init>(Landroid/os/Handler;)V

    iput-object v0, p0, Lfaw/system/viewservice/HVCtrlService;->server:Landroid/os/Messenger;

    .line 68
    sget-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    invoke-direct {p0}, Lfaw/system/viewservice/HVCtrlService;->getMethodName()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v2, ": ipc server OK"

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 70
    new-instance v0, Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

    iget-object v1, p0, Lfaw/system/viewservice/HVCtrlService;->server:Landroid/os/Messenger;

    invoke-direct {v0, v1, p0}, Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;-><init>(Landroid/os/Messenger;Landroid/content/Context;)V

    iput-object v0, p0, Lfaw/system/viewservice/HVCtrlService;->hardKeyManagerServer:Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

    .line 71
    sget-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    invoke-direct {p0}, Lfaw/system/viewservice/HVCtrlService;->getMethodName()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v2, ": HardKeyManagerServer OK"

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    .line 73
    sget-object v0, Lfaw/system/viewservice/HVCtrlService;->TAG:Ljava/lang/String;

    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    invoke-direct {p0}, Lfaw/system/viewservice/HVCtrlService;->getMethodName()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v2, " --> OUT"

    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-static {v0, v1}, Landroid/util/Log;->v(Ljava/lang/String;Ljava/lang/String;)I

    .line 74
    return-void
.end method
