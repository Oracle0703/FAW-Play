.class Lfaw/system/viewservice/HVCtrlService$IncomingHandler;
.super Landroid/os/Handler;
.source "HVCtrlService.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingClass;
    value = Lfaw/system/viewservice/HVCtrlService;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x2
    name = "IncomingHandler"
.end annotation


# instance fields
.field final synthetic this$0:Lfaw/system/viewservice/HVCtrlService;


# direct methods
.method private constructor <init>(Lfaw/system/viewservice/HVCtrlService;)V
    .registers 2

    .line 36
    iput-object p1, p0, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;->this$0:Lfaw/system/viewservice/HVCtrlService;

    invoke-direct {p0}, Landroid/os/Handler;-><init>()V

    return-void
.end method

.method synthetic constructor <init>(Lfaw/system/viewservice/HVCtrlService;Lfaw/system/viewservice/HVCtrlService$1;)V
    .registers 3
    .param p1, "x0"    # Lfaw/system/viewservice/HVCtrlService;
    .param p2, "x1"    # Lfaw/system/viewservice/HVCtrlService$1;

    .line 36
    invoke-direct {p0, p1}, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;-><init>(Lfaw/system/viewservice/HVCtrlService;)V

    return-void
.end method


# virtual methods
.method public handleMessage(Landroid/os/Message;)V
    .registers 4
    .param p1, "msg"    # Landroid/os/Message;

    .line 39
    iget v0, p1, Landroid/os/Message;->what:I

    const/16 v1, 0x1d

    if-eq v0, v1, :cond_21

    packed-switch v0, :pswitch_data_2c

    .line 51
    invoke-super {p0, p1}, Landroid/os/Handler;->handleMessage(Landroid/os/Message;)V

    goto :goto_2b

    .line 45
    :pswitch_d
    iget-object v0, p0, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;->this$0:Lfaw/system/viewservice/HVCtrlService;

    # getter for: Lfaw/system/viewservice/HVCtrlService;->hardKeyManagerServer:Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;
    invoke-static {v0}, Lfaw/system/viewservice/HVCtrlService;->access$000(Lfaw/system/viewservice/HVCtrlService;)Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

    move-result-object v0

    invoke-virtual {v0, p1}, Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;->UnregisterClient(Landroid/os/Message;)V

    .line 46
    goto :goto_2b

    .line 42
    :pswitch_17
    iget-object v0, p0, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;->this$0:Lfaw/system/viewservice/HVCtrlService;

    # getter for: Lfaw/system/viewservice/HVCtrlService;->hardKeyManagerServer:Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;
    invoke-static {v0}, Lfaw/system/viewservice/HVCtrlService;->access$000(Lfaw/system/viewservice/HVCtrlService;)Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

    move-result-object v0

    invoke-virtual {v0, p1}, Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;->RegisterClient(Landroid/os/Message;)V

    .line 43
    goto :goto_2b

    .line 48
    :cond_21
    iget-object v0, p0, Lfaw/system/viewservice/HVCtrlService$IncomingHandler;->this$0:Lfaw/system/viewservice/HVCtrlService;

    # getter for: Lfaw/system/viewservice/HVCtrlService;->hardKeyManagerServer:Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;
    invoke-static {v0}, Lfaw/system/viewservice/HVCtrlService;->access$000(Lfaw/system/viewservice/HVCtrlService;)Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;

    move-result-object v0

    invoke-virtual {v0, p1}, Lfaw/system/viewservice/hardkeymanager/HardKeyManagerServer;->DispatchKey(Landroid/os/Message;)V

    .line 49
    nop

    .line 53
    :goto_2b
    return-void

    :pswitch_data_2c
    .packed-switch 0x10
        :pswitch_17
        :pswitch_d
    .end packed-switch
.end method
