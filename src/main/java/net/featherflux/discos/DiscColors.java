package net.featherflux.discos;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Recolors only the label of the vanilla disc; keeps its transparent silhouette and grooves. */
final class DiscColors {
    record Color(String id,String name,String hex) {}
    static final List<Color> ALL=List.of(
        new Color("ouro","Dourado","#e8bc45"),new Color("vermelho","Vermelho","#e94b54"),
        new Color("laranja","Laranja","#ef8b37"),new Color("lima","Verde-lima","#a7d943"),
        new Color("verde","Verde","#42aa69"),new Color("ciano","Ciano","#3ac9cf"),
        new Color("azul","Azul","#497cdf"),new Color("roxo","Roxo","#a16bdd"),
        new Color("rosa","Rosa","#eb85bd"),new Color("branco","Branco","#ece9dd"),
        new Color("cinza","Cinza","#8b959d"),new Color("marrom","Marrom","#a57650"));
    static Color get(String id) {return ALL.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Cor inválida"));}
    static String validate(String id)throws DiscException{try{return get(id).id();}catch(Exception e){throw new DiscException("Escolha uma das cores disponíveis.");}}
    static byte[] png(String id)throws IOException {
        Color c=get(id);int rgb=Integer.parseInt(c.hex().substring(1),16);
        BufferedImage im;
        try(InputStream in=DiscColors.class.getResourceAsStream("/disc-template.png")){im=ImageIO.read(Objects.requireNonNull(in));}
        for(int y=0;y<im.getHeight();y++)for(int x=0;x<im.getWidth();x++){
            int p=im.getRGB(x,y),r=(p>>16)&255,g=(p>>8)&255,b=p&255;
            if((p>>>24)>0 && Math.max(r,g)-b>25){
                float brightness=Math.max(r,g)/255f;
                int nr=Math.round(((rgb>>16)&255)*brightness),ng=Math.round(((rgb>>8)&255)*brightness),nb=Math.round((rgb&255)*brightness);
                im.setRGB(x,y,(p&0xff000000)|(nr<<16)|(ng<<8)|nb);
            }
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);return out.toByteArray();
    }
}
