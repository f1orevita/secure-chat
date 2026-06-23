package com.securechat.shared;

public class Packet {
    // --- Команди для реєстрації та авторизації ---
    public static final int REGISTER_REQUEST = 101;
    public static final int REGISTER_SUCCESS = 102;
    public static final int REGISTER_ERROR = 103;

    public static final int LOGIN_REQUEST = 201;
    public static final int LOGIN_SUCCESS = 202;
    public static final int LOGIN_ERROR = 203;

    // --- Команди для зміни профілю ---
    public static final int CHANGE_LOGIN_REQUEST = 204;
    public static final int CHANGE_LOGIN_SUCCESS = 205;
    public static final int CHANGE_LOGIN_ERROR = 206;

    public static final int DELETE_ACCOUNT_REQUEST = 207;
    public static final int DELETE_ACCOUNT_SUCCESS = 208;
    public static final int DELETE_ACCOUNT_ERROR = 209;

    // --- Команди для обміну повідомленнями ---
    public static final int SEND_MESSAGE = 301; 
    public static final int NEW_MESSAGE = 302;  
    public static final int HISTORY_SYNC = 303; 
    public static final int MESSAGE_READ_CONFIRM = 304; 
    public static final int ERROR_MESSAGE = 305;

    // --- Команди модерації ---
    public static final int BLOCK_USER = 401;
    public static final int DISCONNECT_KICK = 402;
    public static final int GET_STATISTICS_REQUEST = 403;
    public static final int GET_STATISTICS_RESPONSE = 404;

    // --- Команди для контактів та пошуку ---
    public static final int SEARCH_USER_REQUEST = 501;
    public static final int SEARCH_USER_RESPONSE = 502; 
    public static final int ADD_CONTACT_REQUEST = 503;
    public static final int CONTACT_OPERATION_RESPONSE = 504; 
    public static final int GET_CONTACTS_REQUEST = 505;
    public static final int GET_CONTACTS_RESPONSE = 506; 
    public static final int USER_STATUS_UPDATE = 507; 
    public static final int CHECK_USER_REQUEST = 508;
    public static final int CHECK_USER_RESPONSE = 509;

    // --- НОВІ: Особистий чорний список та видалення контактів ---
    public static final int BLOCK_USER_PERSONAL_REQUEST = 601;
    public static final int UNBLOCK_USER_PERSONAL_REQUEST = 602;
    public static final int REMOVE_CONTACT_REQUEST = 604;
    public static final int GET_BLOCKLIST_REQUEST = 605;
    public static final int GET_BLOCKLIST_RESPONSE = 606;

    // --- Команди для групових чатів ---
    public static final int CREATE_GROUP_REQUEST = 701;
    public static final int ADD_MEMBER_TO_GROUP = 702;
    public static final int SEND_GROUP_MESSAGE = 703;
    public static final int NEW_GROUP_MESSAGE = 704;
    public static final int GET_USER_GROUPS_REQUEST = 705;
    public static final int GET_USER_GROUPS_RESPONSE = 706;
    public static final int LEAVE_GROUP_REQUEST = 707;
    public static final int GET_GROUP_MEMBERS_REQUEST = 708;
    public static final int GET_GROUP_MEMBERS_RESPONSE = 709;

    // --- Команди для статусу друкування ---
    public static final int TYPING_START = 801;
    public static final int TYPING_STOP = 802;

    // --- Системні команди ---
    public static final int PING = 901;
    public static final int PONG = 902;
}