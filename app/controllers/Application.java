package controllers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import models.AudienceManagerAuthentication;
import models.BlogPost;
import models.Comment;
import models.Tag;
import models.User;
import play.Logger;
import play.Play;
import play.api.templates.Html;
import play.data.Form;
import play.libs.F.Callback;
import play.libs.F.Option;
import play.libs.F.Promise;
import play.libs.Json;
import play.libs.WS;
import play.libs.WS.Response;
import play.libs.WS.WSRequestHolder;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;
import views.html.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Blogging platform for demonstrating Adobe AudienceManager APIs.
 * 
 * @author hsahni
 */
public class Application extends Controller {

    /** Login information holder */
    public static class Login {
        public String email;
        public String password;

        public String validate() {
            if (User.authenticate(email, password) == null) {
                return "Invalid user or password";
            }
            return null;
        }
    }

    /** Holds tag information on blog creation in comma separated format */
    public static class TagContainer {
        public String commaSeparatedTags;
    }

    /** Form for commenting on a blog post. */
    static Form<Comment> commentForm = Form.form(Comment.class);

    /** Form for creating new blog posts. */
    static Form<BlogPost> postForm = Form.form(BlogPost.class);

    /** Form for tag information when creating new blog post. */
    static Form<TagContainer> tagContainerForm = Form.form(TagContainer.class);

    /** Form for user registration. */
    static Form<User> userForm = Form.form(User.class);

    /** Presents the user with a login page. */
    public static Result login() {
        return ok(login.render(Form.form(Login.class)));
    }

    /** Logs the user out. */
    public static Result logout() {
        session().clear();
        flash("success", "You've been logged out");
        return redirect(routes.Application.login());
    }

    /** Logs user into application if correct credentials presented. */
    public static Result authenticate() {
        Form<Login> loginForm = Form.form(Login.class).bindFromRequest();
        if (loginForm.hasErrors()) {
            return badRequest(login.render(loginForm));
        } else {
            session().clear();
            session("email", loginForm.get().email);
            return redirect(routes.Application.index());
        }
    }

    /**
     * Returns the logged in user, or null.
     * 
     * @return the user.
     */
    public static User getLoggedInUser() {
        if (session().get("email") == null) {
            return null;
        }
        return User.find.byId(session().get("email"));
    }

    /** Presents the user with a registration page. */
    public static Result register() {
        return ok(views.html.register.render(userForm));
    }

    /** Registers a user. */
    public static Result createUser() {
        Form<User> filledForm = userForm.bindFromRequest();

        if (filledForm.hasErrors()) {
            return badRequest(views.html.register.render(userForm));
        } else {
            User toCreate = filledForm.get();
            User.create(toCreate);
            return redirect(routes.Application.login());
        }
    }

    /** Presents the user with a page to author a new blog post. */
    @Security.Authenticated(Secured.class)
    public static Result authorPost() {
        return ok(views.html.create_post.render(postForm, tagContainerForm));
    }

    /** Presents a single blog post. */
    public static Result getPost(Long id) {
        BlogPost post = BlogPost.find.byId(id);
        if (post == null) {
            return notFound();
        }

        return ok(views.html.single_post.render(BlogPost.find.byId(id),
                Comment.all(), commentForm, getLoggedInUser()));
    }

    /**
     * Renders the navigation header.
     * 
     * @return HTML for the header.
     */
    public static Html header() {
        return views.html.nav.render(getLoggedInUser(), Tag.all(),
                BlogPost.authors(), getAudienceManagerUser());
    }

    /**
     * Gets the audience manager username using the access token for the blog
     * user. Returns null if could not get user.
     * 
     * @return The username in audiencemanager, or null.
     */
    private static String getAudienceManagerUser() {
        String audienceManagerUser = null;
        // This call will always succeed if you have valid audience manager
        // credentials.
        // It's a good way to check that everything is working.
        Response selfResponse = audienceManagerWS("users/self").get().get();
        if (selfResponse.getStatus() == OK) {
            audienceManagerUser = selfResponse.asJson().get("username")
                    .asText();
        }
        return audienceManagerUser;
    }

    /** Displays the main page of the blog. */
    public static Result index() {
        Option<String> noTag = Option.None();
        return redirect(routes.Application.posts(noTag));
    }

    /** Creates a new blog post. */
    @Security.Authenticated(Secured.class)
    public static Result newPost() {
        Form<BlogPost> filledForm = postForm.bindFromRequest();
        Form<TagContainer> filledTagForm = tagContainerForm.bindFromRequest();
        if (filledForm.hasErrors()) {
            return badRequest(views.html.create_post.render(filledForm,
                    filledTagForm));
        } else {
            List<Tag> tags = getTags(filledTagForm.get().commaSeparatedTags);
            BlogPost blogPost = filledForm.get();
            blogPost.tags = tags;
            blogPost.published = new Date();
            blogPost.author = getLoggedInUser();
            BlogPost.create(blogPost);
           
            // Exercise 3
            int datasource = Play.application().configuration()
                    .getInt("audienceManager.datasource");
            int blogPostTraitFolderId = Play.application().configuration()
                    .getInt("audienceManager.postReaderTraitFolder");
            int commentTraitFolderId = Play.application().configuration()
                    .getInt("audienceManager.postCommenterTraitFolder");

            createTrait("Read " + blogPost.title, "post-" + blogPost.id,
                    datasource, blogPostTraitFolderId,
                    "blogPostId==" + blogPost.id).onRedeem(
                    new Callback<Response>() {
                        @Override
                        public void invoke(Response response) throws Throwable {
                            if (response.getStatus() == CREATED) {
                                JsonNode responseJson = response.asJson();
                                Logger.info(String.format(
                                        "Created trait %s for blog post %d",
                                        responseJson, blogPost.id));
                            } else {
                                Logger.error(String
                                        .format("Eror creating trait for blog post %s:\n %d\n %s",
                                                blogPost, response.getStatus(),
                                                response.getBody()));
                            }
                        }
                    });

            createTrait("Commented on " + blogPost.title,
                    "commenter-post-" + blogPost.id, datasource,
                    commentTraitFolderId,
                    "comment==1 AND blogPostId==" + blogPost.id).onRedeem(
                    new Callback<Response>() {

                        @Override
                        public void invoke(Response response) throws Throwable {
                            if (response.getStatus() == CREATED) {
                                JsonNode responseJson = response.asJson();
                                Logger.info(String
                                        .format("Created trait %s for comment on blog post %d",
                                                responseJson, blogPost.id));
                            } else {
                                Logger.error(String
                                        .format("Eror creating trait for comment on blog post %s:\n %d\n %s",
                                                blogPost, response.getStatus(),
                                                response.getBody()));
                            }
                        }
                    });
            
            // Exercise 5 Derived Signals
            createDerivedSignal(blogPost);
            
            return redirect(routes.Application.getPost(blogPost.id));
        }
    }

    /** Creates a new comment on the blog post. */
    @Security.Authenticated(Secured.class)
    public static Result newComment(Long id) {
        Form<Comment> filledForm = commentForm.bindFromRequest();
        BlogPost post = BlogPost.find.byId(id);

        if (filledForm.hasErrors()) {
            return badRequest(views.html.single_post.render(
                    BlogPost.find.byId(id), Comment.all(), filledForm,
                    getLoggedInUser()));
        } else {
            Comment toCreate = filledForm.get();
            toCreate.blogPost = post;
            toCreate.published = new Date();
            toCreate.author = getLoggedInUser();
            Comment.create(toCreate);
            return redirect(routes.Application.getPost(id));
        }
    }

    /**
     * Gets tags from comma separated string. Creates tag as needed.
     * 
     * @param commaSeparatedTags
     * @return
     */
    private static List<Tag> getTags(String commaSeparatedTags) {
        List<Tag> tags = new ArrayList<Tag>();
        String[] tagStrings = commaSeparatedTags.split("\\s*,\\s*");
        for (final String tagString : tagStrings) {
            Tag tag = Tag.find.byId(tagString);
            if (tag == null) {
                // Tag doesn't exist, create
                tag = new Tag();
                tag.creator = getLoggedInUser();
                tag.label = tagString;
                Tag.create(tag);
                
                // Exercise 4
                createTraitForTag(tagString);
            }
            tags.add(tag);
        }
        return tags;
    }

    /** Presents the blog posts in the system. */
    public static Result posts(Option<String> tag) {
        List<BlogPost> blogPosts = tag.isEmpty() ? BlogPost.byDate() : BlogPost
                .byDateForTag(Tag.find.byId(tag.get()));
        return ok(views.html.index.render(blogPosts));
    }

    /** Presents the user page for the user */
    public static Result user(String userId) {
        User user = User.find.byId(userId);
        return ok(views.html.user.render(user, BlogPost.byDateForAuthor(user)));
    }

    /**
     * Sets standard headers for accessing AudienceManager APIs for
     * authentication and content type negotiation.
     * 
     * @param resourcePath
     *            the resource path to be accessed. This will be combined with
     *            the
     * @return
     */
    public static WSRequestHolder audienceManagerWS(String resourcePath) {
        String url = Play.application().configuration()
                .getString("audienceManager.apiBase")
                + resourcePath;
        WSRequestHolder audienceManagerWSRH = WS.url(url)
                .setContentType("application/json")
                .setHeader("Accept", "application/json");
        // Set authorization header to user's access token if it exists.
        if (getLoggedInUser() != null
                && AudienceManagerAuthentication.forUser(getLoggedInUser()) != null) {
            audienceManagerWSRH = audienceManagerWSRH.setHeader(
                    "Authorization",
                    "Bearer "
                            + AudienceManagerAuthentication
                                    .forUser(getLoggedInUser()).accessToken);
        }

        return audienceManagerWSRH;
    }
    
    /**
     * Creates a trait in AudienceManager. Added as part of Exercise 3.
     * 
     * @param name
     *            The name of the trait.
     * @param integrationCode
     *            The integration code of the trait.
     * @param datasourceId
     *            The data source for the trait
     * @param folderId
     *            The folder the trait will contain the trait.
     * @param traitRule
     *            The trait rule.
     * @return
     */
    private static Promise<Response> createTrait(String name,
            String integrationCode, int datasourceId, int folderId,
            String traitRule) {

        ObjectNode trait = Json.newObject();
        trait.put("name", name);
        // Using an integration makes accessing the corresponding blog post
        // trait in AudienceManager easy. We don't need to keep track of the id
        // in audience manager and can use our own ids
        // for finding the trait.
        trait.put("integrationCode", integrationCode);
        trait.put("dataSourceId", datasourceId);
        trait.put("folderId", folderId);
        trait.put("traitType", "RULE_BASED_TRAIT");
        trait.put("traitRule", traitRule);

        return audienceManagerWS("traits/").post(trait);
    }
    
    /**
     * Creates a trait for a tag. Added as part of Exercise 4.
     * 
     * @param tagString
     *            The tag
     */
    private static void createTraitForTag(final String tagString) {
        int datasource = Play.application().configuration()
                .getInt("audienceManager.datasource");
        int tagTraitFolderId = Play.application().configuration()
                .getInt("audienceManager.tagTraitFolder");

        ObjectNode trait = Json.newObject();
        trait.put("name", "Read Post Tagged as " + tagString);
        // Using an integration makes accessing the corresponding blog post
        // trait in AudienceManager easy. We don't need to keep track of the id
        // in audience manager and can use our own ids
        // for finding the trait.
        trait.put("integrationCode", "tag-" + tagString);
        trait.put("dataSourceId", datasource);
        trait.put("folderId", tagTraitFolderId);
        trait.put("traitType", "RULE_BASED_TRAIT");
        trait.put("traitRule", "tag==\"" + tagString + "\"");

        audienceManagerWS("traits").post(trait).onRedeem(
                new Callback<Response>() {
                    @Override
                    public void invoke(Response response) throws Throwable {
                        if (response.getStatus() == CREATED) {
                            AudienceManagerAuthentication aamAuth = new AudienceManagerAuthentication();
                            JsonNode responseJson = response.asJson();
                            Logger.info(String.format(
                                    "Created trait %s for tag %s",
                                    responseJson, tagString));
                        } else {
                            Logger.error(String.format(
                                    "Eror creating trait for tag: %d\n %s",
                                    response.getStatus(), response.getBody()));
                        }
                    }
                });
    }
    
    /**
     * Creates a derived signal that expands a blog post id into the author's
     * email address. Added as part of Exercise 5.
     * 
     * @param blogPost
     *            The blog post
     */
    private static void createDerivedSignal(BlogPost blogPost) {
        ObjectNode derivedSignal = Json.newObject();
        derivedSignal.put("sourceKey", "blogPostId");
        derivedSignal.put("sourceValue", blogPost.id);
        derivedSignal.put("targetKey", "author");
        derivedSignal.put("targetValue", blogPost.author.email);

        audienceManagerWS("signals/derived/").post(derivedSignal).onRedeem(
                new Callback<Response>() {
                    @Override
                    public void invoke(Response response) throws Throwable {
                        if (response.getStatus() == CREATED) {
                            JsonNode responseJson = response.asJson();
                            Logger.info(String.format(
                                    "Created derivedSignal %s for post %s",
                                    responseJson, blogPost.title));
                        } else {
                            Logger.error(String
                                    .format("Eror creating derivedSignal for tag: %d\n %s",
                                            response.getStatus(),
                                            response.getBody()));
                        }
                    }
                });
    }

}
