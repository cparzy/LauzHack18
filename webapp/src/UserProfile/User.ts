export default class User {
    private _name: string;
    private _avatarUrl: string;
    private _topics: string[];
    private _languages: string[];

    constructor(name: string, avatarUrl: string, topics: string[], languages: string[]) {
        this._name = name;
        this._avatarUrl = avatarUrl;
        this._topics = topics;
        this._languages = languages;
    }

    public get name(): string { return this._name; }
    public get avatarUrl(): string { return this._avatarUrl; }
    public get topics(): string[] { return this._topics; }
    public get languages(): string[] { return this._languages; }
}